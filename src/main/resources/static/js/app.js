(() => {
  'use strict';

  /* ================= constants ================= */

  const LS_JDK = 'dsa.jdkHome';
  const LS_LEVEL = 'dsa.level';
  const LS_LINT = 'dsa.lint';
  const LS_SUGGEST = 'dsa.suggest';
  const LS_VIM = 'dsa.vim';
  const LS_THEME = 'algolab.theme';
  const LS_LAYOUT = 'algolab.layout';
  const LS_DSA = 'algolab.dsaMigrate'; // '1' = never ask to rename .dsa files

  const EXT_NOTEBOOK = '.algolab';
  const EXT_LEGACY = '.dsa';

  const DEFAULT_CODE = 'public class Main {\n\tpublic static void main(String[] args) {\n\t\t\n\t}\n}\n';

  /* ================= state ================= */

  const state = {
    root: '',
    rootName: '',

    docs: [],          // open files: [{ path, name, code, meta, testCases, selectedCase, selectedOutputCase, results, dirty }]
    activePath: null,  // path of the visible doc (also the one in the editor)

    jdks: [],          // [{ home, version, name, versionLine, levels }]
    jdkHome: null,

    // Where Java actually runs: 'local' | 'judge0' | 'none' (from /api/jdks).
    execution: { mode: 'none', modeLabel: '', judge0: { configured: false, url: '' } },

    loading: false,    // true while programmatically setting editor value

    editor: null,
    editorReady: false,
    vim: null,         // monaco-vim instance (when enabled)
    monacoVim: null,   // monaco-vim module, when it loaded

    treeCache: new Map(),
    expanded: new Set(),

    runInFlight: false,

    lintEnabled: localStorage.getItem(LS_LINT) !== '0',
    suggestEnabled: localStorage.getItem(LS_SUGGEST) !== '0',

    lintTimer: null,
    syntaxTimer: null,
    lintSeq: 0,
    lastLintKey: '',
  };

  /* ================= tiny DOM helpers ================= */

  const $ = (sel) => document.querySelector(sel);

  function el(tag, cls, text) {
    const node = document.createElement(tag);
    if (cls) node.className = cls;
    if (text !== undefined && text !== null) node.textContent = text;
    return node;
  }

  function escapeHtml(str) {
    return String(str)
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  let toastTimer;
  function toast(msg) {
    const t = $('#toast');
    t.textContent = msg;
    t.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.remove('show'), 2600);
  }

  /* ================= API ================= */

  async function api(path, opts) {
    const res = await fetch(path, opts);
    let data = null;
    try { data = await res.json(); } catch (e) { /* no JSON body */ }
    if (!res.ok) {
      const msg = (data && (data.error || data.message)) || `HTTP ${res.status}`;
      throw new Error(msg);
    }
    return data;
  }

  /* ================= open docs (file tabs) ================= */

  function activeDoc() {
    return state.docs.find((d) => d.path === state.activePath) || null;
  }

  function getDoc(path) {
    return state.docs.find((d) => d.path === path) || null;
  }

  function stripExt(name) {
    return String(name).replace(/\.(algolab|dsa)$/i, '');
  }

  /** True when path points at an openable notebook (.algolab or legacy .dsa). */
  function isNotebookPath(path) {
    return /\.(algolab|dsa)$/i.test(path || '');
  }

  /** Path with a legacy .dsa extension swapped for .algolab (same directory). */
  function algolabPathOf(path) {
    const i = (path || '').lastIndexOf('/');
    const parent = i < 0 ? '' : path.slice(0, i + 1);
    const file = path.slice(i + 1);
    return parent + file.replace(/\.dsa$/i, '') + EXT_NOTEBOOK;
  }

  function newDoc(path, name) {
    return {
      path,
      name,
      code: '',
      meta: { name: stripExt(name), language: 'java', javaVersion: null },
      testCases: [],
      nextCaseNum: 1,   // per-doc: id seed for the next created case
      selectedCase: 0,
      selectedOutputCase: 0,
      results: {},
      dirty: false,
    };
  }

  /** Parse the on-disk content into a doc. Empty/raw notebooks get the skeleton. */
  function populateDoc(doc, content) {
    let parsed = null;
    try { parsed = JSON.parse(content); } catch (e) { parsed = null; }

    if (parsed && typeof parsed.code === 'string') {
      doc.code = parsed.code;
      doc.testCases = normalizeTestCases(doc, parsed.testCases);
      doc.meta.name = typeof parsed.name === 'string' ? parsed.name : stripExt(doc.name);
      doc.meta.language = typeof parsed.language === 'string' ? parsed.language : 'java';
      const jv = parsed.javaVersion;
      const num = jv == null ? null : Number(jv);
      doc.meta.javaVersion = Number.isInteger(num) && num > 0 ? num : null;
    } else if (!String(content).trim()) {
      // Brand-new / empty notebook -> a fresh notebook with the Main skeleton.
      doc.code = DEFAULT_CODE;
      doc.testCases = normalizeTestCases(doc, []);
      doc.dirty = true;
      toast('New file — press Save (⌘/Ctrl+S) to write it');
    } else {
      doc.code = content;
      doc.testCases = normalizeTestCases(doc, []);
      toast('Not valid notebook JSON — opened as raw code (wrapped on save)');
    }
    alignDocLevel(doc);
  }

  function markDirty() {
    const doc = activeDoc();
    if (!doc) return;
    doc.dirty = true;
    updateDirty();
    renderFileTabs();
  }

  function updateDirty() {
    const doc = activeDoc();
    document.title = ((doc && doc.dirty) ? '● ' : '') +
      (doc ? doc.name : 'AlgoLab');
  }

  function updateWelcome() {
    $('#welcome').classList.toggle('hidden', !!activeDoc());
  }

  function setEditorValue(code) {
    if (!state.editor) return;
    state.loading = true;
    state.editor.setValue(code);
    state.loading = false;
  }

  /** Snapshot whatever is in the editor back into the active doc. */
  function syncEditorToDoc(doc) {
    if (doc && state.editor) doc.code = state.editor.getValue();
  }

  function renderFileTabs() {
    const bar = $('#file-tabs');
    bar.innerHTML = '';
    if (!state.docs.length) {
      bar.classList.add('hidden');
      bar.classList.remove('flex');
      return;
    }
    bar.classList.remove('hidden');
    bar.classList.add('flex');
    for (const doc of state.docs) {
      const active = doc.path === state.activePath;
      const tab = el('div',
        'file-tab flex items-center gap-1.5 pl-2 pr-1 py-1 rounded text-xs cursor-pointer select-none whitespace-nowrap border ' +
        (active ? 'active' : ''));
      tab.title = doc.path;
      tab.append(
        el('span', 'shrink-0 opacity-70', '📄'),
        el('span', 'max-w-[150px] truncate', doc.name));
      if (doc.dirty) {
        tab.appendChild(el('span', 'dirty-dot shrink-0', '●'));
      }
      const close = el('button', 'close-tab', '✕');
      close.title = 'Close tab';
      close.addEventListener('click', (ev) => { ev.stopPropagation(); closeDoc(doc.path); });
      tab.addEventListener('click', () => {
        if (doc.path !== state.activePath) activateDoc(doc);
      });
      tab.addEventListener('auxclick', (ev) => {
        if (ev.button === 1) { ev.preventDefault(); closeDoc(doc.path); }
      });
      tab.appendChild(close);
      bar.appendChild(tab);
    }
  }

  function activateDoc(doc) {
    syncEditorToDoc(activeDoc());
    state.activePath = doc.path;
    state.loading = true;
    if (state.editor) state.editor.setValue(doc.code);
    state.loading = false;
    alignDocLevel(doc);
    renderLevelSelect();
    updateWelcome();
    updateDirty();
    renderFileTabs();
    renderCaseTabs();
    renderCaseEditor();
    renderOutput();
    clearMarkers();
    scheduleSyntax(120);
    scheduleLint(120);
  }

  /** The UI state when no file is open. */
  function renderEmptyState() {
    state.activePath = null;
    updateWelcome();
    updateDirty();
    renderFileTabs();
    renderCaseTabs();
    renderCaseEditor();
    renderOutput();
    clearMarkers();
    renderLevelSelect();
  }

  /** Floating modal: legacy .dsa files are deprecated — offer renaming to .algolab. */
  function promptDsaMigration(path) {
    if (!/\.dsa$/i.test(path)) return Promise.resolve('asis');
    if (localStorage.getItem(LS_DSA) === '1') return Promise.resolve('asis');
    return new Promise((resolve) => {
      const modal = $('#dsa-modal');
      const skip = $('#dsa-skip');
      $('#dsa-modal-file').textContent = path;
      skip.checked = false;
      modal.classList.remove('hidden');

      let done = false;
      const onKey = (e) => { if (e.key === 'Escape') finish(null); };
      const onBackdrop = (e) => { if (e.target === modal) finish(null); };
      const finish = (val) => {
        if (done) return;
        done = true;
        window.removeEventListener('keydown', onKey);
        modal.removeEventListener('mousedown', onBackdrop);
        modal.classList.add('hidden');
        if (skip.checked) {
          localStorage.setItem(LS_DSA, '1');
          persistSettingsFile();
        }
        resolve(val);
      };
      window.addEventListener('keydown', onKey);
      modal.addEventListener('mousedown', onBackdrop);
      $('#dsa-btn-rename').addEventListener('click', () => finish('rename'));
      $('#dsa-btn-asis').addEventListener('click', () => finish('asis'));
      $('#dsa-btn-cancel').addEventListener('click', () => finish(null));
    });
  }

  /** Rename a legacy .dsa file to .algolab on disk. Resolves the new path or null. */
  async function renameDsaToAlgoLab(path) {
    const newPath = algolabPathOf(path);
    try {
      await api('/api/fs/rename', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ path, newName: newPath.slice(newPath.lastIndexOf('/') + 1) }),
      });
      return newPath;
    } catch (e) {
      toast('Could not rename ' + path + ': ' + e.message);
      return null;
    }
  }

  async function openFile(path) {
    if (!path) return;
    // Legacy .dsa → ask once whether to migrate to .algolab before opening.
    if (/\.dsa$/i.test(path)) {
      const action = await promptDsaMigration(path);
      if (action === null) return; // cancelled
      if (action === 'rename') {
        const renamed = await renameDsaToAlgoLab(path);
        if (!renamed) return;
        path = renamed;
        refreshTree();
        toast('Renamed to ' + renamed.slice(renamed.lastIndexOf('/') + 1));
      }
    }
    doOpen(path);
  }

  /** Read a notebook file into the editor (path must already be migrated if needed). */
  async function doOpen(path) {
    if (!path) return;
    const existing = getDoc(path);
    if (existing) {
      if (existing.path !== state.activePath) activateDoc(existing);
      return;
    }
    let data;
    try {
      data = await api('/api/fs/read?path=' + encodeURIComponent(path));
    } catch (e) {
      toast('Could not open: ' + e.message);
      return;
    }
    const doc = newDoc(path, data.name);
    populateDoc(doc, data.content);
    state.docs.push(doc);
    activateDoc(doc);
  }

  /** Close a tab. force=true skips the unsaved-changes prompt. */
  function closeDoc(path, opts) {
    opts = opts || {};
    const idx = state.docs.findIndex((d) => d.path === path);
    if (idx < 0) return false;
    const doc = state.docs[idx];
    if (!opts.force && doc.dirty && !confirm(`“${doc.name}” has unsaved changes. Close without saving?`)) {
      return false;
    }
    syncEditorToDoc(doc);
    const wasActive = doc.path === state.activePath;
    state.docs.splice(idx, 1);

    if (!state.docs.length) {
      renderEmptyState();
      return true;
    }
    if (wasActive) {
      const next = state.docs[Math.min(idx, state.docs.length - 1)];
      activateDoc(next);
    } else {
      renderFileTabs();
    }
    return true;
  }

  /** Remove every open doc that lives at path (or inside a folder of that path). */
  function dropDocsUnder(path) {
    let activeGone = false;
    state.docs = state.docs.filter((d) => {
      const gone = d.path === path || d.path.startsWith(path + '/');
      if (gone && d.path === state.activePath) activeGone = true;
      return !gone;
    });
    if (!state.docs.length) {
      renderEmptyState();
    } else if (activeGone) {
      state.activePath = null;
      const next = state.docs[0];
      state.activePath = next.path;
      if (state.editor) setEditorValue(next.code);
      alignDocLevel(next);
      updateWelcome();
      updateDirty();
      renderFileTabs();
      renderCaseTabs();
      renderCaseEditor();
      renderOutput();
      clearMarkers();
      scheduleLint(120);
    }
  }

  /* ================= file tree ================= */

  async function childrenOf(dirPath) {
    if (!state.treeCache.has(dirPath)) {
      state.treeCache.set(dirPath, await api('/api/fs/list?path=' + encodeURIComponent(dirPath)));
    }
    return state.treeCache.get(dirPath);
  }

  async function buildRows(dirPath, depth, out) {
    if (!state.expanded.has(dirPath)) return;
    const entries = await childrenOf(dirPath);
    for (const e of entries) {
      out.push({ entry: e, depth });
      if (e.directory && state.expanded.has(e.path)) {
        await buildRows(e.path, depth + 1, out);
      }
    }
  }

  async function renderTree() {
    const tree = $('#tree');
    tree.innerHTML = '';
    tree.appendChild(makeRow({ name: state.rootName || state.root, path: '', directory: true }, 0));
    const rows = [];
    try {
      await buildRows('', 1, rows);
      for (const { entry, depth } of rows) tree.appendChild(makeRow(entry, depth));
    } catch (e) {
      tree.appendChild(el('p', 'px-3 py-2 text-xs text-red-400', 'Could not load tree: ' + e.message));
    }
  }

  async function toggleDir(path) {
    if (state.expanded.has(path)) {
      state.expanded.delete(path);
      await renderTree();
      return;
    }
    state.expanded.add(path);
    await renderTree();
  }

  function makeRow(entry, depth) {
    const row = el('div', 'tree-row flex items-center gap-1.5 pr-2 py-[3px] rounded cursor-pointer hover:bg-neutral-800/60 text-neutral-300 whitespace-nowrap');
    row.style.paddingLeft = (8 + depth * 14) + 'px';

    if (entry.directory) {
      const arrow = el('span', 'w-3 text-center text-neutral-500 shrink-0',
        state.expanded.has(entry.path) ? '▾' : '▸');
      const icon = el('span', 'shrink-0', '📁');
      const label = el('span', 'truncate', entry.name);
      row.append(arrow, icon, label);
      row.addEventListener('click', () => toggleDir(entry.path));
    } else {
      const spacer = el('span', 'w-3 shrink-0', '');
      const icon = el('span', 'shrink-0', entry.isNotebook ? '📄' : '🗎');
      const label = el('span', 'truncate', entry.name);
      row.append(spacer, icon, label);
      if (entry.isNotebook) {
        label.classList.add('text-amber-200');
        row.addEventListener('click', () => openFile(entry.path));
      } else {
        row.classList.add('opacity-40', 'cursor-not-allowed');
        row.title = 'Not a notebook — only .algolab / .dsa files can be opened';
      }
    }
    row.addEventListener('contextmenu', (e) => {
      e.preventDefault();
      e.stopPropagation();
      showContextMenu(e.clientX, e.clientY, menuForEntry(entry));
    });
    return row;
  }

  /* ================= context menu + file ops ================= */

  const ctxMenu = el('div', 'ctx-menu hidden fixed z-50 min-w-[180px] bg-neutral-800 border border-neutral-700 rounded-md shadow-xl py-1 select-none');
  document.body.appendChild(ctxMenu);

  function showContextMenu(x, y, items) {
    ctxMenu.innerHTML = '';
    for (const item of items) {
      if (item === '---') {
        ctxMenu.appendChild(el('div', 'h-px my-1 bg-neutral-700'));
        continue;
      }
      const btn = el('button',
        'block w-full text-left px-3 py-1.5 text-xs whitespace-nowrap hover:bg-neutral-700 ' +
        (item.danger ? 'text-red-400' : 'text-neutral-200') +
        (item.disabled ? ' opacity-40 cursor-not-allowed' : ''));
      btn.textContent = item.label;
      btn.disabled = !!item.disabled;
      btn.addEventListener('click', () => {
        hideContextMenu();
        if (!item.disabled && item.action) item.action();
      });
      ctxMenu.appendChild(btn);
    }
    ctxMenu.classList.remove('hidden');
    const rect = ctxMenu.getBoundingClientRect();
    const px = Math.max(4, Math.min(x, window.innerWidth - rect.width - 4));
    const py = Math.max(4, Math.min(y, window.innerHeight - rect.height - 4));
    ctxMenu.style.left = px + 'px';
    ctxMenu.style.top = py + 'px';
  }

  function hideContextMenu() {
    ctxMenu.classList.add('hidden');
  }

  function copyPath(path) {
    const text = path || '(workspace root)';
    const done = () => toast('Copied: ' + text);
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(done, () => fallbackCopy(text, done));
    } else {
      fallbackCopy(text, done);
    }
  }

  function fallbackCopy(text, done) {
    const ta = el('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    try { document.execCommand('copy'); } catch (e) { /* ignore */ }
    ta.remove();
    done();
  }

  function menuForEntry(entry) {
    if (entry.directory) {
      const isRoot = entry.path === '';
      const items = [
        { label: 'New .algolab File…', action: () => promptCreate(entry.path, false, '.algolab') },
        { label: 'New File…', action: () => promptCreate(entry.path, false) },
        { label: 'New Folder…', action: () => promptCreate(entry.path, true) },
        '---',
        { label: 'Rename…', disabled: isRoot, action: () => promptRename(entry) },
        { label: 'Duplicate…', disabled: isRoot, action: () => duplicateEntry(entry) },
        { label: 'Delete', danger: true, disabled: isRoot, action: () => deleteEntry(entry) },
        '---',
        { label: 'Copy Path', action: () => copyPath(entry.path) },
        { label: 'Refresh', action: refreshTree },
      ];
      return items;
    }
    return [
      { label: 'Open', disabled: !entry.isNotebook, action: () => openFile(entry.path) },
      '---',
      { label: 'Duplicate…', action: () => duplicateEntry(entry) },
      { label: 'Rename…', action: () => promptRename(entry) },
      { label: 'Delete', danger: true, action: () => deleteEntry(entry) },
      '---',
      { label: 'Copy Path', action: () => copyPath(entry.path) },
    ];
  }

  function joinPath(parent, name) {
    return parent ? parent + '/' + name : name;
  }

  function parentOf(path) {
    const i = path.lastIndexOf('/');
    return i < 0 ? '' : path.slice(0, i);
  }

  async function refreshTree() {
    state.treeCache.clear();
    await renderTree();
  }

  async function promptCreate(parentPath, isDir, requiredExt) {
    const kind = isDir ? 'folder' : 'file';
    const hint = requiredExt ? ' (must end with ' + requiredExt + ')' : '';
    const name = await promptName('New ' + kind, (isDir ? 'Folder name' : 'File name') + hint);
    if (!name) return;
    let fileName = name;
    if (requiredExt && !fileName.toLowerCase().endsWith(requiredExt)) {
      fileName += requiredExt;
    }
    const path = joinPath(parentPath, fileName);
    try {
      await api('/api/fs/create', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ path, directory: isDir }),
      });
      state.treeCache.delete(parentPath);
      state.expanded.add(parentPath); // reveal the new entry
      await renderTree();
      if (!isDir && isNotebookPath(fileName)) openFile(path);
      toast('Created ' + kind + ': ' + fileName);
    } catch (e) {
      toast('Could not create ' + kind + ': ' + e.message);
    }
  }

  async function promptRename(entry) {
    const name = await promptName('Rename', entry.name, entry.name);
    if (!name || name === entry.name) return;
    const oldPath = entry.path;
    try {
      await api('/api/fs/rename', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ path: oldPath, newName: name }),
      });
      // Keep any open docs that live under the renamed path in sync.
      const parent = parentOf(oldPath);
      const renamed = new Map(); // old path -> new path
      for (const doc of state.docs) {
        if (doc.path === oldPath) {
          const newPath = joinPath(parent, name);
          renamed.set(oldPath, newPath);
          doc.path = newPath;
          doc.name = name;
          doc.meta.name = stripExt(name);
        } else if (doc.path.startsWith(oldPath + '/')) {
          const newPath = joinPath(parent, name) + doc.path.slice(oldPath.length);
          renamed.set(doc.path, newPath);
          doc.path = newPath;
        }
      }
      if (renamed.has(state.activePath)) {
        state.activePath = renamed.get(state.activePath);
      }
      state.treeCache.clear();
      await renderTree();
      updateDirty();
      renderFileTabs();
      toast('Renamed to ' + name);
    } catch (e) {
      toast('Rename failed: ' + e.message);
    }
  }

  async function duplicateEntry(entry) {
    try {
      const res = await api('/api/fs/duplicate', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ path: entry.path }),
      });
      state.treeCache.clear();
      await renderTree();
      const created = res.path || '';
      if (!entry.directory && isNotebookPath(created)) openFile(created);
      toast('Duplicated → ' + created);
    } catch (e) {
      toast('Duplicate failed: ' + e.message);
    }
  }

  async function deleteEntry(entry) {
    const what = entry.directory ? 'folder' : 'file';
    const msg = entry.directory
      ? 'Delete folder “' + entry.name + '” and all of its contents?'
      : 'Delete “' + entry.name + '”?';
    if (!confirm(msg)) return;
    try {
      await api('/api/fs/delete', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ path: entry.path }),
      });
      dropDocsUnder(entry.path);
      state.treeCache.clear();
      await renderTree();
      toast('Deleted ' + what + ': ' + entry.name);
    } catch (e) {
      toast('Delete failed: ' + e.message);
    }
  }

  function promptName(title, placeholder, initial) {
    return new Promise((resolve) => {
      const overlay = el('div', 'fixed inset-0 z-50 flex items-center justify-center bg-black/50');
      const box = el('div', 'w-80 bg-neutral-800 border border-neutral-700 rounded-lg shadow-2xl p-4');
      const h = el('p', 'text-sm font-medium text-neutral-100 mb-3', title);
      const input = el('input', 'input-dark');
      input.value = initial || '';
      input.placeholder = placeholder || '';
      input.spellcheck = false;
      const err = el('p', 'hidden text-xs text-red-400 mt-2');
      const btns = el('div', 'flex justify-end gap-2 mt-4');
      const cancel = el('button', 'px-3 py-1.5 rounded text-xs text-neutral-300 hover:bg-neutral-700', 'Cancel');
      const ok = el('button', 'px-3 py-1.5 rounded text-xs font-medium bg-emerald-600 hover:bg-emerald-500 text-white', 'Create');
      btns.append(cancel, ok);
      box.append(h, input, err, btns);
      overlay.appendChild(box);
      document.body.appendChild(overlay);

      let done = false;
      const finish = (val) => {
        if (done) return;
        done = true;
        overlay.remove();
        resolve(val);
      };
      const submit = () => {
        const val = input.value.trim();
        if (!val || val === '.' || val === '..' || val.includes('/') || val.includes('\\')) {
          err.textContent = 'Name must not be empty and cannot contain / or \\';
          err.classList.remove('hidden');
          return;
        }
        finish(val);
      };
      cancel.addEventListener('click', () => finish(null));
      ok.addEventListener('click', submit);
      input.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') submit();
        else if (e.key === 'Escape') finish(null);
      });
      overlay.addEventListener('mousedown', (e) => { if (e.target === overlay) finish(null); });
      input.focus();
      input.select();
    });
  }

  /* ================= JDK + language level pickers ================= */

  function currentJdk() {
    return state.jdks.find((j) => j.home === state.jdkHome) || null;
  }

  function selectedLevel() {
    const raw = $('#java-level-select').value;
    return raw === '' ? null : Number(raw);
  }

  async function loadJdks() {
    let data;
    try {
      data = await api('/api/jdks');
    } catch (e) {
      return;
    }
    state.jdks = data.jdks || [];
    state.execution = {
      mode: data.mode || 'none',
      modeLabel: data.modeLabel || '',
      judge0: (data.judge0 && typeof data.judge0 === 'object') ? data.judge0 : { configured: false, url: '' },
    };
    const def = data.default;
    const saved = localStorage.getItem(LS_JDK);
    const picked =
      state.jdks.find((j) => j.home === saved) ||
      (def && state.jdks.find((j) => j.home === def.home)) ||
      state.jdks[0] ||
      null;
    renderJdkSelect(picked);
    renderExecMode();
  }

  /**
   * Small status-badge showing where Java runs. Purely informational — the
   * app prefers the local JDK and only uses Judge0 when none exists.
   */
  function renderExecMode() {
    const badge = $('#exec-mode');
    if (!badge) return;
    const m = state.execution.mode;
    let cls, label, title;
    if (m === 'local') {
      cls = 'text-emerald-400';
      label = 'Local JDK';
      title = 'Running Java with a JDK found on this machine';
    } else if (m === 'judge0') {
      cls = 'text-amber-400';
      label = 'Judge0 (remote)';
      title = 'No local JDK — running via Judge0 at ' + (state.execution.judge0.url || '');
    } else {
      cls = 'text-red-400';
      label = 'No executor';
      title = 'No JDK found and Judge0 not configured — install a JDK or set JUDGE0_API_URL';
    }
    badge.className = 'font-mono ' + cls;
    badge.textContent = '● ' + label;
    badge.title = title;
    badge.classList.remove('hidden');
  }

  function renderJdkSelect(picked) {
    const sel = $('#jdk-select');
    sel.innerHTML = '';
    if (!state.jdks.length) {
      // No local JDK: remote (Judge0) execution is what runs the code.
      const opt = el('option');
      opt.disabled = true;
      opt.textContent = 'No JDK found — running via Judge0';
      sel.appendChild(opt);
      state.jdkHome = null;
      sel.value = '';
      renderLevelSelect();
      return;
    }
    for (const jdk of state.jdks) {
      const opt = el('option');
      opt.value = jdk.home;
      opt.textContent = 'Java ' + jdk.version + ' · ' + jdk.name;
      opt.title = jdk.versionLine || jdk.home;
      sel.appendChild(opt);
    }
    if (picked) {
      state.jdkHome = picked.home;
      sel.value = picked.home;
    } else {
      state.jdkHome = state.jdks.length ? state.jdks[0].home : null;
    }
    renderLevelSelect();
  }

  /**
   * Repopulate the Java-level dropdown for the current JDK and pick the level:
   * the active doc's stored level if supported, else the saved default, else the JDK's own.
   */
  function renderLevelSelect() {
    const sel = $('#java-level-select');
    const jdk = currentJdk();
    // Judge0 images run Java 17 — without a local JDK that's the only level
    // worth offering; it's stored in the notebook like any other.
    const levels = (jdk && jdk.levels) ||
      (state.execution.mode === 'judge0' ? [17] : []);
    sel.innerHTML = '';
    if (!levels.length) {
      const opt = el('option');
      opt.disabled = true;
      opt.textContent = '—';
      sel.appendChild(opt);
      sel.value = '';
      return;
    }
    for (const level of levels) {
      const opt = el('option');
      opt.value = String(level);
      opt.textContent = 'Java ' + level;
      sel.appendChild(opt);
    }
    let pick = null;
    const doc = activeDoc();
    if (doc && doc.meta.javaVersion != null && levels.includes(doc.meta.javaVersion)) {
      pick = doc.meta.javaVersion;
    } else {
      const stored = Number(localStorage.getItem(LS_LEVEL));
      if (levels.includes(stored)) pick = stored;
    }
    if (pick == null) pick = levels.length ? levels[0] : null;
    sel.value = pick == null ? '' : String(pick);
  }

  /**
   * Make sure the active doc's stored level matches what the current JDK can do.
   * Called when a doc becomes active or when the JDK changes.
   */
  function alignDocLevel(doc) {
    if (!doc) return;
    const jdk = currentJdk();
    // With no local JDK the remote (Judge0) Java-17 default is what runs.
    const levels = (jdk && jdk.levels && jdk.levels.length)
      ? jdk.levels
      : (state.execution.mode === 'judge0' ? [17] : []);
    if (!levels.length) return;
    if (doc.meta.javaVersion == null) {
      doc.meta.javaVersion = levels[0] || null; // adopt the default silently
      return;
    }
    if (!levels.includes(doc.meta.javaVersion)) {
      const before = doc.meta.javaVersion;
      doc.meta.javaVersion = levels[0] || null;
      if (!doc.dirty && doc.code) {
        doc.dirty = true;
        updateDirty();
        renderFileTabs();
        toast(`Java ${before} not available — using Java ${doc.meta.javaVersion}`);
      }
    }
  }

  function onJdkChange() {
    state.jdkHome = $('#jdk-select').value;
    localStorage.setItem(LS_JDK, state.jdkHome);
    persistSettingsFile();
    const doc = activeDoc();
    if (doc) alignDocLevel(doc);
    renderLevelSelect();
    scheduleLint(120);
  }

  function onLevelChange() {
    const level = selectedLevel();
    const doc = activeDoc();
    if (level != null) {
      localStorage.setItem(LS_LEVEL, String(level));
      persistSettingsFile();
    }
    if (doc && doc.meta.javaVersion !== level) {
      doc.meta.javaVersion = level;
      markDirty();
    }
    scheduleLint(120);
  }

  /* ================= test cases ================= */

  /**
   * Create a new test case for {@code doc}. Ids are per-document (case-1…N,
   * seeded from the cases already in this doc) so tabs never inherit numbers
   * from other notebooks; the default display name counts the cases in this
   * doc (N cases → next is "Case N+1"), without reusing a number still shown
   * by an existing "Case N" tab.
   */
  function newCase(doc, name) {
    const id = 'case-' + (doc.nextCaseNum++);
    let defaultNum = 0;
    if (!name) {
      for (const tc of doc.testCases) {
        const m = /^Case (\d+)$/.exec(String(tc.name || ''));
        if (m) defaultNum = Math.max(defaultNum, Number(m[1]));
      }
      defaultNum = Math.max(doc.testCases.length + 1, defaultNum + 1);
    }
    return {
      id,
      name: name || 'Case ' + defaultNum,
      stdin: '',
      expectedEnabled: false,
      expected: '',
    };
  }

  /**
   * Normalize a doc's persisted test cases: unique per-doc ids, safe names,
   * and a per-doc next-id seed derived from what this doc actually contains.
   * An empty list yields one default "Case 1" (fresh-notebook behavior).
   */
  function normalizeTestCases(doc, raw) {
    const list = Array.isArray(raw) ? raw : [];
    const seen = new Set();
    const out = list.map((tc, i) => {
      let id = tc && tc.id ? String(tc.id) : 'case-' + (i + 1);
      if (seen.has(id)) id = id + '-' + i;
      seen.add(id);
      const expected = tc && typeof tc.expected === 'string' ? tc.expected : '';
      return {
        id,
        name: tc && tc.name ? String(tc.name) : 'Case ' + (i + 1),
        stdin: tc && typeof tc.stdin === 'string' ? tc.stdin : '',
        expectedEnabled: expected !== '',
        expected,
      };
    });
    // Seed the per-doc id counter past every numeric case-N id present (so
    // reloading never collides) and past the case count (so the first new
    // default name continues the visible sequence).
    let maxNum = 0;
    for (const tc of out) {
      const m = /^case-(\d+)$/.exec(tc.id);
      if (m) maxNum = Math.max(maxNum, Number(m[1]));
    }
    doc.nextCaseNum = Math.max(out.length + 1, maxNum + 1);
    return out.length ? out : [newCase(doc, 'Case 1')];
  }

  function renderCaseTabs() {
    const wrap = $('#case-tabs');
    wrap.innerHTML = '';
    const doc = activeDoc();
    if (!doc) return;
    doc.testCases.forEach((tc, i) => {
      const active = i === doc.selectedCase;
      const tab = el('div',
        'flex items-center gap-1 px-2.5 py-1 rounded text-xs cursor-pointer select-none whitespace-nowrap border ' +
        (active
          ? 'bg-neutral-800 text-neutral-100 border-neutral-700'
          : 'text-neutral-400 border-transparent hover:bg-neutral-800/60'));
      const name = el('span', 'max-w-[90px] truncate', tc.name || 'untitled');
      const close = el('button', 'text-neutral-500 hover:text-red-400 ml-0.5', '×');
      close.title = 'Delete test case';
      close.addEventListener('click', (ev) => { ev.stopPropagation(); removeCase(tc.id); });
      tab.addEventListener('click', () => {
        doc.selectedCase = i;
        renderCaseTabs();
        renderCaseEditor();
      });
      tab.append(name, close);
      wrap.appendChild(tab);
    });
  }

  function renderCaseEditor() {
    const body = $('#case-body');
    body.innerHTML = '';
    const doc = activeDoc();
    if (!doc) {
      const p = el('p', 'text-neutral-600 text-xs');
      p.append('Open a ', el('span', 'text-amber-400', '.algolab'), ' file to edit its test cases.');
      body.appendChild(p);
      return;
    }
    const tc = doc.testCases[doc.selectedCase];
    if (!tc) return;

    // name
    body.appendChild(el('p', 'field-label', 'Name'));
    const nameInput = el('input', 'input-dark');
    nameInput.value = tc.name;
    nameInput.placeholder = 'e.g. Normal case';
    nameInput.addEventListener('input', () => {
      tc.name = nameInput.value;
      markDirty();
      renderCaseTabs(); // tabs show the name; focus stays in this input
    });
    body.appendChild(nameInput);

    // stdin
    body.appendChild(el('p', 'field-label', 'stdin'));
    const stdin = el('textarea', 'input-dark h-36');
    stdin.value = tc.stdin;
    stdin.placeholder = '5\n4 2 7 1 3';
    stdin.spellcheck = false;
    stdin.addEventListener('input', () => { tc.stdin = stdin.value; markDirty(); });
    body.appendChild(stdin);
    body.appendChild(el('p', 'text-[11px] text-neutral-600 mt-1', 'Piped to the program via System.in.'));

    // expected output
    const chkRow = el('label', 'flex items-center gap-2 mt-4 cursor-pointer select-none');
    const chk = el('input', 'accent-emerald-500 w-3.5 h-3.5');
    chk.type = 'checkbox';
    chk.checked = tc.expectedEnabled;
    chk.addEventListener('change', () => {
      tc.expectedEnabled = chk.checked;
      markDirty();
      renderCaseEditor();
    });
    chkRow.append(chk, el('span', 'text-sm text-neutral-300', 'Expected output'));
    body.appendChild(chkRow);

    if (tc.expectedEnabled) {
      body.appendChild(el('p', 'field-label', 'Expected stdout'));
      const exp = el('textarea', 'input-dark h-28');
      exp.value = tc.expected;
      exp.placeholder = '1 2 3 4 7';
      exp.spellcheck = false;
      exp.addEventListener('input', () => { tc.expected = exp.value; markDirty(); });
      body.appendChild(exp);
      body.appendChild(el('p', 'text-[11px] text-neutral-600 mt-1',
        'Checked against the program’s stdout after the run.'));
    } else {
      body.appendChild(el('p', 'text-[11px] text-neutral-600 mt-1',
        'No expected output — the run will still show what the program printed.'));
    }
  }

  function addCase() {
    const doc = activeDoc();
    if (!doc) return toast('Open a .algolab file first');
    const tc = newCase(doc);
    doc.testCases.push(tc);
    doc.selectedCase = doc.testCases.length - 1;
    markDirty();
    renderCaseTabs();
    renderCaseEditor();
    renderOutput();
  }

  function removeCase(id) {
    const doc = activeDoc();
    if (!doc) return;
    if (doc.testCases.length <= 1) return toast('Keep at least one test case');
    doc.testCases = doc.testCases.filter((t) => t.id !== id);
    delete doc.results[id];
    doc.selectedCase = Math.min(doc.selectedCase, doc.testCases.length - 1);
    markDirty();
    renderCaseTabs();
    renderCaseEditor();
    renderOutput();
  }

  /* ================= output pane ================= */

  function verdict(tc, r) {
    if (!r) return { cls: 'text-neutral-600', icon: '—', label: 'no output' };
    switch (r.status) {
      case 'RUNNING': return { cls: 'text-sky-400', icon: '…', label: 'running' };
      case 'PASS': return tc.expectedEnabled
        ? { cls: 'text-emerald-400', icon: '✓', label: 'pass' }
        : { cls: 'text-neutral-300', icon: '▸', label: 'ran' };
      case 'FAIL': return tc.expectedEnabled
        ? { cls: 'text-red-400', icon: '✗', label: 'fail' }
        : { cls: 'text-neutral-300', icon: '▸', label: 'ran' };
      case 'COMPILE_ERROR': return { cls: 'text-red-400', icon: '✗', label: 'compile error' };
      case 'TIMEOUT': return { cls: 'text-amber-400', icon: '⏱', label: 'timeout' };
      case 'ERROR': return { cls: 'text-red-400', icon: '✗', label: 'error' };
      default: return { cls: 'text-neutral-300', icon: '▸', label: 'ran' };
    }
  }

  function renderOutput() {
    const tabs = $('#output-tabs');
    tabs.innerHTML = '';
    const doc = activeDoc();
    if (!doc) {
      renderOutputBody();
      return;
    }
    doc.testCases.forEach((tc, i) => {
      const v = verdict(tc, doc.results[tc.id]);
      const active = i === doc.selectedOutputCase;
      const tab = el('div',
        'flex items-center gap-1.5 px-2.5 py-1 rounded text-xs cursor-pointer select-none whitespace-nowrap border ' +
        (active
          ? 'bg-neutral-800 text-neutral-100 border-neutral-700'
          : 'text-neutral-400 border-transparent hover:bg-neutral-800/60'));
      tab.append(
        el('span', v.cls + ' font-bold', v.icon),
        el('span', 'max-w-[80px] truncate', tc.name || 'untitled'));
      tab.addEventListener('click', () => {
        doc.selectedOutputCase = i;
        renderOutput();
      });
      tabs.appendChild(tab);
    });
    renderOutputBody();
  }

  function renderOutputBody() {
    const body = $('#output-body');
    const doc = activeDoc();
    body.innerHTML = '';

    if (!doc) {
      const p = el('p', 'text-neutral-600');
      p.append('Open a ', el('span', 'text-amber-400', '.algolab'), ' file — its test case results appear here.');
      body.appendChild(p);
      return;
    }
    const tc = doc.testCases[doc.selectedOutputCase];
    if (!tc) return;

    const r = doc.results[tc.id];
    if (!r) {
      const p = el('p', 'text-neutral-600');
      p.append('No output yet — press ', el('span', 'text-emerald-400', '▶ Run'), '.');
      body.appendChild(p);
      return;
    }

    const v = verdict(tc, r);
    const head = el('div', 'flex items-center gap-2 mb-2');
    head.append(
      el('span', v.cls + ' font-bold text-sm', v.icon),
      el('span', v.cls + ' text-xs font-semibold uppercase tracking-wide', v.label));
    if (r.durationMs != null) {
      head.appendChild(el('span', 'text-neutral-500 text-xs ml-auto', r.durationMs + ' ms'));
    }
    body.appendChild(head);

    if (r.status === 'RUNNING') {
      body.appendChild(el('p', 'text-sky-400', 'Running…'));
      return;
    }
    if (r.error) {
      body.appendChild(el('pre', 'out-block text-red-300', escapeHtml(r.error)));
    }
    if (r.stdout) {
      body.appendChild(el('p', 'field-label', 'stdout'));
      body.appendChild(el('pre', 'out-block', escapeHtml(r.stdout)));
    }
    if (r.stderr) {
      body.appendChild(el('p', 'field-label text-red-400', 'stderr'));
      body.appendChild(el('pre', 'out-block text-red-300', escapeHtml(r.stderr)));
    }
    if (!r.error && !r.stdout && !r.stderr) {
      body.appendChild(el('p', 'text-neutral-600', '(no output)'));
    }
  }

  /* ================= save ================= */

  function buildDsaDoc(doc) {
    const dsa = {
      name: doc.meta.name || stripExt(doc.name),
      language: doc.meta.language || 'java',
      javaVersion: doc.meta.javaVersion != null ? doc.meta.javaVersion : null,
      code: doc.code,
      testCases: doc.testCases.map((tc) => ({
        id: tc.id,
        name: tc.name,
        stdin: tc.stdin,
        expected: tc.expectedEnabled ? tc.expected : null,
      })),
    };
    return JSON.stringify(dsa, null, 2) + '\n';
  }

  /** Save the active file. Resolves true on success. */
  async function saveFile() {
    const doc = activeDoc();
    if (!doc) {
      toast('Nothing to save — open a .algolab file first');
      return false;
    }
    syncEditorToDoc(doc);
    try {
      await api('/api/fs/save', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ path: doc.path, content: buildDsaDoc(doc) }),
      });
      doc.dirty = false;
      updateDirty();
      renderFileTabs();
      toast('Saved ' + doc.name);
      return true;
    } catch (e) {
      toast('Save failed: ' + e.message);
      return false;
    }
  }

  /* ================= run ================= */

  async function runAll() {
    const doc = activeDoc();
    if (!doc) return toast('Open a .algolab file first');
    if (state.runInFlight) return;
    syncEditorToDoc(doc);
    state.runInFlight = true;
    setRunBusy(true);

    // Running saves the file first, so the notebook on disk matches the run.
    await saveFile();

    doc.results = {};
    for (const tc of doc.testCases) {
      doc.results[tc.id] = { status: 'RUNNING', stdout: '', stderr: '', error: null };
    }
    renderOutput();
    try {
      await streamRun(doc);
    } catch (e) {
      const msg = e.message;
      for (const tc of doc.testCases) {
        if (doc.results[tc.id] && doc.results[tc.id].status === 'RUNNING') {
          doc.results[tc.id] = { status: 'ERROR', stdout: '', stderr: '', error: msg };
        }
      }
    } finally {
      state.runInFlight = false;
      setRunBusy(false);
      renderOutput();
    }
  }

  /**
   * POST the run and read the newline-delimited JSON response, painting each
   * test-case verdict into the output pane the moment it arrives (the backend
   * flushes one line per finished case instead of one response at the end).
   */
  function streamRun(doc) {
    return new Promise((resolve, reject) => {
      fetch('/api/run', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          path: doc.path,
          code: doc.code,
          testCases: doc.testCases.map((tc) => ({
            id: tc.id,
            name: tc.name,
            stdin: tc.stdin,
            expected: tc.expectedEnabled ? tc.expected : null,
          })),
          jdk: state.jdkHome,
          javaVersion: selectedLevel(),
        }),
      }).then(async (res) => {
        if (!res.ok) {
          let data = null;
          try { data = await res.json(); } catch (e) { /* not JSON */ }
          throw new Error((data && (data.error || data.message)) || `HTTP ${res.status}`);
        }
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let buffer = '';
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          buffer += decoder.decode(value, { stream: true });
          let nl;
          while ((nl = buffer.indexOf('\n')) >= 0) {
            const line = buffer.slice(0, nl).trim();
            buffer = buffer.slice(nl + 1);
            if (!line) continue;
            try {
              const result = JSON.parse(line);
              const tc = doc.testCases.find((t) => t.id === result.id);
              if (tc) {
                doc.results[result.id] = result;
                renderOutput();
              }
            } catch (e) { /* skip malformed line */ }
          }
        }
        resolve();
      }).catch((e) => reject(e));
    });
  }

  function setRunBusy(busy) {
    const btn = $('#run-btn');
    btn.disabled = busy;
    btn.textContent = busy ? 'Running…' : '▶ Run';
    btn.classList.toggle('is-running', busy);
  }

  /* ================= live diagnostics =================
   * Two tiers:
   *   1. 'syntax'  — instant client-side pass over the tree-sitter parse that
   *      is already kept for highlighting (ERROR / missing-token nodes).
   *   2. 'semantic' — the real javac pass over the wire (/api/lint), now fast
   *      because the backend compiles in-process (~20-30ms warm).
   * Markers are cleared on every edit so stale squiggles never linger, and a
   * sequence number drops responses for superseded code.
   */

  const MARKER_SYNTAX = 'syntax';
  const MARKER_SEMANTIC = 'semantic';
  const SYNTAX_DEBOUNCE = 150;
  const LINT_DEBOUNCE = 400;

  function scheduleLint(delay) {
    clearTimeout(state.lintTimer);
    if (!state.lintEnabled) return;
    state.lintTimer = setTimeout(runLint, delay == null ? LINT_DEBOUNCE : delay);
  }

  function scheduleSyntax(delay) {
    clearTimeout(state.syntaxTimer);
    if (!state.lintEnabled) return;
    state.syntaxTimer = setTimeout(runSyntax, delay == null ? SYNTAX_DEBOUNCE : delay);
  }

  /** Invoked on every change: kill in-flight responses and clear both layers. */
  function invalidateMarkers() {
    state.lintSeq += 1; // responses for older code are dropped at apply time
    clearMarkers();
  }

  /** Tier 1: mark tree-sitter ERROR/MISSING nodes immediately. */
  function runSyntax() {
    const doc = activeDoc();
    const model = state.editor && state.editor.getModel();
    if (!doc || !model || !state.lintEnabled) return;
    const tree = window.AlgoLabTS && window.AlgoLabTS.tree(model);
    const markers = [];
    if (tree) collectSyntaxMarkers(tree.root, tree.text, model, markers);
    const converted = markers.map((m) => ({
      severity: monaco.MarkerSeverity.Error,
      message: 'Syntax error',
      startLineNumber: m.line,
      startColumn: m.column,
      endLineNumber: m.line,
      endColumn: Math.min(m.column + 1, model.getLineLength(m.line) + 1),
    }));
    monaco.editor.setModelMarkers(model, MARKER_SYNTAX, converted);
  }

  /** Walk the parse tree for ERROR nodes and missing tokens. */
  function collectSyntaxMarkers(node, text, model, out) {
    if (!node) return;
    if (node.type === 'ERROR' || node.isMissing) {
      const pos = node.startPosition; // { row, column } — column is in bytes
      const line = pos.row + 1;
      const column = utf16Column(text, pos.row, pos.column);
      if (line >= 1 && column >= 1) {
        out.push({ line: Math.min(line, model.getLineCount()), column });
        return; // don't double-report nested errors
      }
    }
    for (let i = 0; i < node.childCount; i++) {
      collectSyntaxMarkers(node.child(i), text, model, out);
    }
  }

  /** tree-sitter columns are byte offsets; Monaco columns are UTF-16 units. */
  function utf16Column(text, row, byteCol) {
    let lineStart = 0;
    for (let r = 0; r < row; r++) {
      lineStart = text.indexOf('\n', lineStart);
      if (lineStart < 0) return 1;
      lineStart += 1;
    }
    let bytes = 0;
    let units = 1;
    const end = Math.min(lineStart + 1_000_000, text.length);
    for (let i = lineStart; i < end; i++) {
      if (bytes >= byteCol) break;
      const c = text.charCodeAt(i);
      if (c === 10 || c === 13) break;
      bytes += c > 0x7ff ? 3 : c > 0x7f ? 2 : 1;
      units += 1;
    }
    return units;
  }

  /** Tier 2: semantic markers from the backend's javac pass. */
  async function runLint() {
    const doc = activeDoc();
    if (!doc || !state.editor || !state.lintEnabled) return;
    syncEditorToDoc(doc);
    const jdk = state.jdkHome || '';
    const level = selectedLevel();
    const key = jdk + '|' + level + '|' + doc.code;
    if (key === state.lastLintKey) return;
    state.lastLintKey = key;
    const seq = ++state.lintSeq;
    try {
      const res = await api('/api/lint', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code: doc.code, jdk, javaVersion: level }),
      });
      if (seq !== state.lintSeq) return;
      const stillActive = activeDoc();
      if (!stillActive || stillActive.path !== doc.path) return;
      applyMarkers((res && res.markers) || []);
    } catch (e) {
      // transient backend hiccup — keep whatever markers were already shown
    }
  }

  function applyMarkers(list) {
    const model = state.editor && state.editor.getModel();
    if (!model) return;
    const converted = list.map((m) => ({
      severity: m.severity === 'error'
        ? monaco.MarkerSeverity.Error
        : monaco.MarkerSeverity.Warning,
      message: (m.severity === 'error' ? '' : '[warning] ') + m.message,
      startLineNumber: Math.max(1, m.line || 1),
      startColumn: Math.max(1, m.column || 1),
      endLineNumber: Math.max(1, m.line || 1),
      endColumn: Math.max(1, m.column || 1) + 1,
    }));
    monaco.editor.setModelMarkers(model, MARKER_SEMANTIC, converted);

    const errors = list.filter((m) => m.severity === 'error').length;
    const warnings = list.length - errors;
    const badge = $('#problems');
    if (list.length) {
      const parts = [];
      if (errors) parts.push(errors + ' error' + (errors > 1 ? 's' : ''));
      if (warnings) parts.push(warnings + ' warning' + (warnings > 1 ? 's' : ''));
      badge.textContent = '⚠ ' + parts.join(' · ');
      badge.classList.remove('hidden');
    } else {
      badge.classList.add('hidden');
    }
  }

  function clearMarkers() {
    state.lastLintKey = '';
    const model = state.editor && state.editor.getModel();
    if (model && window.monaco && monaco.editor) {
      monaco.editor.setModelMarkers(model, MARKER_SEMANTIC, []);
      monaco.editor.setModelMarkers(model, MARKER_SYNTAX, []);
    }
    const badge = $('#problems');
    if (badge) badge.classList.add('hidden');
  }

  function toggleLint(on) {
    state.lintEnabled = on;
    localStorage.setItem(LS_LINT, on ? '1' : '0');
    persistSettingsFile();
    if (on) {
      scheduleSyntax(80);
      scheduleLint(80);
    } else {
      clearTimeout(state.lintTimer);
      clearTimeout(state.syntaxTimer);
      clearMarkers();
    }
  }

  /* ================= vim mode ================= */

  function toggleVim(on, MonacoVim) {
    if (!MonacoVim) {
      toast('Vim mode unavailable');
      $('#vim-toggle').checked = false;
      return;
    }
    if (on) {
      if (!state.vim) {
        state.vim = MonacoVim.initVimMode(state.editor, $('#vim-status'));
      }
      $('#vim-status').classList.remove('hidden');
      $('#status-right').textContent = 'Vim: on';
    } else {
      if (state.vim) {
        state.vim.dispose();
        state.vim = null;
      }
      $('#vim-status').classList.add('hidden');
      $('#status-right').textContent = 'Vim: off';
    }
    localStorage.setItem(LS_VIM, on ? '1' : '0');
    persistSettingsFile();
  }

  /**
   * :w / :q / :wq / :q! — real ex commands on top of monaco-vim's CodeMirror core.
   * monaco-vim registers the vim API as VimMode.Vim; defineEx adds a command to its
   * dispatcher (the built-in `write` is a no-op without CodeMirror's save hook, and
   * quit/wq don't exist at all).
   */
  function installVimExCommands(MonacoVim) {
    const Vim = MonacoVim && MonacoVim.VimMode && MonacoVim.VimMode.Vim;
    if (!Vim || typeof Vim.defineEx !== 'function') return false;

    Vim.defineEx('write', 'w', () => {
      saveFile();
    });

    Vim.defineEx('quit', 'q', (cm, params) => {
      const doc = activeDoc();
      if (!doc) {
        toast('Nothing to close');
        return;
      }
      const bang = !!(params && (params.argString || '').includes('!'));
      if (doc.dirty && !bang) {
        toast('Unsaved changes — use :q! to close without saving');
        return;
      }
      closeDoc(doc.path, { force: true });
    });

    Vim.defineEx('wq', 'wq', async () => {
      const doc = activeDoc();
      if (!doc) {
        toast('Nothing to save — open a .algolab file first');
        return;
      }
      const saved = await saveFile();
      if (saved) closeDoc(doc.path, { force: true });
    });

    return true;
  }

  /* ================= offline Java suggestions ================= */

  const JAVA_COMPLETIONS = [
    { label: 'main', kind: 'snippet', snippet: true,
      insertText: 'public static void main(String[] args) {\n\t$0\n}',
      detail: 'main method', doc: 'Entry point' },
    { label: 'sout', kind: 'snippet', snippet: true,
      insertText: 'System.out.println($0);', detail: 'print line', doc: 'System.out.println' },
    { label: 'soutf', kind: 'snippet', snippet: true,
      insertText: 'System.out.printf($0);', detail: 'print formatted', doc: 'System.out.printf' },
    { label: 'fori', kind: 'snippet', snippet: true,
      insertText: 'for (int i = 0; i < $1; i++) {\n\t$0\n}',
      detail: 'indexed for loop', doc: 'Classic int-index for loop' },
    { label: 'foreach', kind: 'snippet', snippet: true,
      insertText: 'for (int $1 : $2) {\n\t$0\n}',
      detail: 'enhanced for', doc: 'for-each over an int[] / Collection' },
    { label: 'while', kind: 'snippet', snippet: true,
      insertText: 'while ($1) {\n\t$0\n}', detail: 'while loop', doc: 'while loop' },
    { label: 'ifelse', kind: 'snippet', snippet: true,
      insertText: 'if ($1) {\n\t$0\n} else {\n\t\n}',
      detail: 'if / else', doc: 'if with else branch' },
    { label: 'pclass', kind: 'snippet', snippet: true,
      insertText: 'public class $1 {\n\t$0\n}',
      detail: 'public class', doc: 'New top-level class' },
    { label: 'scanner', kind: 'snippet', snippet: true,
      insertText: 'Scanner sc = new Scanner(System.in);',
      detail: 'stdin Scanner', doc: 'Scanner over System.in (import java.util.*)' },
    { label: 'import util', kind: 'snippet', snippet: true,
      insertText: 'import java.util.*;', detail: 'java.util.*', doc: 'Wildcard import for collections/Scanner' },

    { label: 'Arrays', kind: 'class',
      insertText: 'Arrays', detail: 'java.util.Arrays', doc: 'Arrays.sort / Arrays.toString / Arrays.copyOf / Arrays.fill' },
    { label: 'Arrays.sort', kind: 'method',
      insertText: 'Arrays.sort($0);', detail: 'sort array', doc: 'Sorts an array in place' },
    { label: 'Arrays.toString', kind: 'method',
      insertText: 'Arrays.toString($0)', detail: 'array to string', doc: 'Human-readable array dump' },
    { label: 'ArrayList', kind: 'class', insertText: 'ArrayList', detail: 'java.util.ArrayList' },
    { label: 'HashMap', kind: 'class', insertText: 'HashMap', detail: 'java.util.HashMap' },
    { label: 'HashSet', kind: 'class', insertText: 'HashSet', detail: 'java.util.HashSet' },
    { label: 'LinkedList', kind: 'class', insertText: 'LinkedList', detail: 'java.util.LinkedList' },
    { label: 'PriorityQueue', kind: 'class', insertText: 'PriorityQueue', detail: 'java.util.PriorityQueue' },
    { label: 'Stack', kind: 'class', insertText: 'Stack', detail: 'java.util.Stack' },
    { label: 'ArrayDeque', kind: 'class', insertText: 'ArrayDeque', detail: 'java.util.ArrayDeque (Deque)' },
    { label: 'StringBuilder', kind: 'class', insertText: 'StringBuilder', detail: 'java.lang.StringBuilder' },
    { label: 'Integer', kind: 'class', insertText: 'Integer', detail: 'java.lang.Integer' },
    { label: 'Math', kind: 'class', insertText: 'Math', detail: 'java.lang.Math' },
  ];

  function registerJavaCompletions() {
    if (!window.monaco || !monaco.languages) return;
    const kindMap = {
      snippet: monaco.languages.CompletionItemKind.Snippet,
      keyword: monaco.languages.CompletionItemKind.Keyword,
      class: monaco.languages.CompletionItemKind.Class,
      method: monaco.languages.CompletionItemKind.Function,
    };
    monaco.languages.registerCompletionItemProvider('java', {
      provideCompletionItems(model, position) {
        if (!state.suggestEnabled) return { suggestions: [] };
        const word = model.getWordUntilPosition(position);
        const lineBefore = model.getLineContent(position.lineNumber).slice(0, word.startColumn - 1);
        // After a receiver dot the member engine (java-completions.js) owns the
        // suggestions — don't mix in unrelated snippets/keywords there.
        const engineActive = !!(window.AlgoLabCompletions && window.AlgoLabCompletions.active());
        if (engineActive && /\.\s*$/.test(lineBefore)) return { suggestions: [] };
        const range = {
          startLineNumber: position.lineNumber,
          endLineNumber: position.lineNumber,
          startColumn: word.startColumn,
          endColumn: word.endColumn,
        };
        return {
          suggestions: JAVA_COMPLETIONS.map((c) => ({
            label: c.label,
            kind: kindMap[c.kind] || monaco.languages.CompletionItemKind.Text,
            detail: c.detail,
            documentation: c.doc || c.detail || '',
            insertText: c.insertText,
            insertTextRules: c.snippet
              ? monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet
              : monaco.languages.CompletionItemInsertTextRule.None,
            range,
          })),
        };
      },
    });
  }

  function toggleSuggest(on) {
    state.suggestEnabled = on;
    localStorage.setItem(LS_SUGGEST, on ? '1' : '0');
    persistSettingsFile();
  }

  /** Java syntax highlighting comes from monaco's basic-languages (vendored). */
  function registerJavaLanguageOnce() {
    if (!window.monaco || !window.require) return;
    const already = (monaco.languages.getLanguages() || []).some((l) => l.id === 'java');
    if (already) return;
    require(['vs/basic-languages/java/java'], (mod) => {
      try {
        monaco.languages.register({ id: 'java' });
        monaco.languages.setLanguageConfiguration('java', mod.conf);
        monaco.languages.setMonarchTokensProvider('java', mod.language);
      } catch (e) {
        console.warn('Java language registration failed:', e);
      }
    }, (err) => {
      console.warn('Java language module not found:', err);
    });
  }

  /* ================= editor bootstrap ================= */

  function initEditor(MonacoVim) {
    installVimExCommands(MonacoVim);
    registerJavaCompletions();
    if (window.AlgoLabCompletions) window.AlgoLabCompletions.register();
    applyTheme(storedTheme()); // register palettes + apply the persisted theme before first paint

    state.editor = monaco.editor.create($('#editor'), {
      value: (activeDoc() && activeDoc().code) || '',
      language: 'java',
      theme: (state.theme && state.theme.id) || 'one-dark',
      fontFamily: "ui-monospace, SFMono-Regular, Menlo, Consolas, 'Liberation Mono', monospace",
      fontSize: 14,
      lineHeight: 20,
      minimap: { enabled: false },
      wordWrap: 'off',
      scrollBeyondLastLine: false,
      tabSize: 4,
      insertSpaces: true,
      renderWhitespace: 'selection',
      lineNumbersMinChars: 3,
      padding: { top: 8 },
      bracketPairColorization: { enabled: true },
      guides: { bracketPairs: true, indentation: true },
      automaticLayout: true,
    });

    state.editor.onDidChangeModelContent(() => {
      if (state.loading) return;
      const doc = activeDoc();
      if (!doc) return;
      doc.dirty = true;
      updateDirty();
      renderFileTabs();
      invalidateMarkers();      // clear both layers + drop in-flight responses
      scheduleSyntax();         // tier 1: instant tree-sitter pass
      scheduleLint();           // tier 2: fast semantic pass
    });

    $('#lint-toggle').addEventListener('change', (e) => toggleLint(e.target.checked));
    $('#suggest-toggle').addEventListener('change', (e) => toggleSuggest(e.target.checked));

    // Ctrl/Cmd+S = save, Ctrl/Cmd+Enter = run
    state.editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => saveFile());
    state.editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, runAll);

    $('#vim-toggle').addEventListener('change', (e) => toggleVim(e.target.checked, MonacoVim));
    $('#run-btn').addEventListener('click', runAll);
    $('#save-btn').addEventListener('click', () => saveFile());

    // reflect persisted lint / suggest preferences on the toggle buttons
    $('#lint-toggle').checked = state.lintEnabled;
    $('#suggest-toggle').checked = state.suggestEnabled;
    if (!state.lintEnabled) clearMarkers();

    // apply a vim preference that arrived from the settings file (or is in localStorage)
    state.monacoVim = MonacoVim;
    applyVimPref(MonacoVim);

    state.editorReady = true;

    // If a file was opened before the editor finished loading, show it now.
    const doc = activeDoc();
    if (doc) {
      setEditorValue(doc.code);
      clearMarkers();
      scheduleLint(120);
    }
    renderFileTabs();
    updateWelcome();
    updateDirty();
    renderCaseTabs();
    renderCaseEditor();
    renderOutput();

    // auto-open a file passed via ?open=... (used by the dsa-playground launcher)
    const openParam = new URLSearchParams(location.search).get('open');
    if (openParam) {
      openFile(openParam);
    }
  }

  /* ================= chrome themes ================= */

  const THEMES = (window.ALGOLAB_THEMES) || [];

  function themeById(id) {
    return THEMES.find((t) => t.id === id) || null;
  }

  function storedTheme() {
    try { return localStorage.getItem(LS_THEME) || 'system'; } catch (e) { return 'system'; }
  }

  function systemPrefersDark() {
    return !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
  }

  /** Resolve a preference ('system' or a theme id) into concrete {pref, id, mode}. */
  function resolveTheme(pref) {
    const p = pref || storedTheme();
    const t = themeById(p);
    if (t && t.mode !== 'auto') return { pref: p, id: t.id, mode: t.mode };
    const mode = systemPrefersDark() ? 'dark' : 'light';
    const id = mode === 'dark' ? window.ALGOLAB_DEFAULT_DARK : window.ALGOLAB_DEFAULT_LIGHT;
    return { pref: 'system', id, mode };
  }

  function monacoThemeColors(c, mode) {
    return {
      'editor.background': c.bg,
      'editor.foreground': c.fg,
      'editorLineNumber.foreground': c.comment,
      'editorLineNumber.activeForeground': c.func,
      'editorCursor.foreground': c.func,
      'editor.selectionBackground': c.func + '55',
      'editor.inactiveSelectionBackground': c.func + '30',
      'editor.lineHighlightBackground': mode === 'dark' ? '#ffffff10' : '#0000000d',
      'editorIndentGuide.background1': mode === 'dark' ? '#ffffff1c' : '#0000001a',
      'editorIndentGuide.activeBackground1': c.func + '59',
      'editorWidget.background': c.bg,
      'editorWidget.border': mode === 'dark' ? '#3f3f46' : '#d4d4d8',
      'editorSuggestWidget.background': c.bg,
      'editorSuggestWidget.border': mode === 'dark' ? '#3f3f46' : '#d4d4d8',
      'editorHoverWidget.background': c.bg,
      'focusBorder': c.func,
      'scrollbarSlider.background': mode === 'dark' ? '#ffffff24' : '#0000002b',
      'scrollbarSlider.hoverBackground': mode === 'dark' ? '#ffffff3a' : '#00000045',
      'scrollbarSlider.activeBackground': mode === 'dark' ? '#ffffff55' : '#0000005c',
    };
  }

  /** Register every palette as a Monaco theme (id = theme id). Safe to call repeatedly. */
  function defineAlgoLabThemes() {
    if (!window.monaco || !monaco.editor) return;
    for (const t of THEMES) {
      if (t.mode === 'auto' || !t.c) continue;
      const c = t.c;
        const x = (window.ALGOLAB_EXTRA) ? window.ALGOLAB_EXTRA(c) : {};
      const rules = [
        // base roles (Monarch + tree-sitter share these roots via prefix match)
        { token: 'comment', foreground: c.comment, fontStyle: 'italic' },
        { token: 'string', foreground: c.string },
        { token: 'string.escape', foreground: c.number },
        { token: 'number', foreground: c.number },
        { token: 'keyword', foreground: c.keyword },
        { token: 'type', foreground: c.type },
        { token: 'type.identifier', foreground: c.type },
        { token: 'identifier', foreground: c.fg },
        { token: 'function', foreground: c.func },
        { token: 'operator', foreground: c.fg },
        { token: 'delimiter', foreground: c.fg },
        { token: 'annotation', foreground: c.type },
        // tree-sitter semantic distinctions (richer than a regex tokenizer)
        { token: 'keyword.modifier', foreground: c.keyword },
        { token: 'constant', foreground: x.constC || c.number },
        { token: 'constant.language', foreground: c.keyword },
        { token: 'type.primitive', foreground: c.type },
        { token: 'type.parameter', foreground: c.type },
        { token: 'function.method', foreground: c.func },
        { token: 'function.call', foreground: c.func },
        { token: 'function.constructor', foreground: c.type },
        { token: 'property', foreground: x.prop || c.fg },
        { token: 'variable', foreground: c.fg },
        { token: 'variable.local', foreground: c.fg },
        { token: 'variable.parameter', foreground: c.fg, fontStyle: 'italic' },
        { token: 'variable.field', foreground: x.prop || c.fg },
        { token: 'namespace', foreground: x.ns || c.comment },
        { token: 'comment.tag', foreground: c.type },
      ];
      try {
        monaco.editor.defineTheme(t.id, {
          base: t.mode === 'dark' ? 'vs-dark' : 'vs',
          inherit: false,
          rules,
          colors: monacoThemeColors(c, t.mode),
        });
      } catch (e) {
        console.warn('Failed to define theme ' + t.id, e);
      }
    }
  }

  function renderThemeGrid() {
    const grid = $('#theme-grid');
    if (!grid) return;
    grid.innerHTML = '';
    const label = (txt) => {
      const s = el('div', 'menu-group-title');
      s.textContent = txt;
      s.style.gridColumn = '1 / -1';
      return s;
    };
    const option = (t) => {
      const b = el('button', 'theme-opt');
      b.type = 'button';
      b.dataset.theme = t.id;
      const sw = el('span', 'sw');
      if (t.mode === 'auto') {
        b.style.gridColumn = '1 / -1';
        sw.style.background = 'linear-gradient(135deg, #18181b 50%, #f5f6f8 50%)';
        const nm = el('span', 'tname', 'System');
        nm.appendChild(el('small', '', 'Follow the OS'));
        b.append(sw, nm);
      } else {
        sw.style.background = t.bg;
        const nm = el('span', 'tname', t.label);
        nm.appendChild(el('small', '', t.mode === 'dark' ? 'Dark' : 'Light'));
        b.append(sw, nm);
      }
      b.addEventListener('click', () => applyTheme(t.id));
      return b;
    };

    grid.appendChild(option(themeById('system')));
    grid.appendChild(label('Dark'));
    for (const t of THEMES) if (t.mode === 'dark') grid.appendChild(option(t));
    grid.appendChild(label('Light'));
    for (const t of THEMES) if (t.mode === 'light') grid.appendChild(option(t));
    updateThemeSel();
  }

  function updateThemeSel() {
    const grid = $('#theme-grid');
    if (!grid || !state.theme) return;
    grid.querySelectorAll('.theme-opt').forEach((b) =>
      b.classList.toggle('sel', b.dataset.theme === state.theme.pref));
  }

  /** Persist the preference, swap chrome mode + Monaco theme, re-highlight the grid. */
  function applyTheme(pref) {
    state.theme = resolveTheme(pref);
    try { localStorage.setItem(LS_THEME, state.theme.pref); } catch (e) { /* ignore */ }
    persistSettingsFile();
    document.documentElement.dataset.mode = state.theme.mode;
    document.documentElement.dataset.theme = state.theme.id;
    // Paint the whole chrome from the active palette (surfaces, text, accents).
    if (window.ALGOLAB_APPLY_CHROME) window.ALGOLAB_APPLY_CHROME(state.theme.id);
    if (window.monaco && monaco.editor) {
      defineAlgoLabThemes();
      try { monaco.editor.setTheme(state.theme.id); } catch (e) { console.warn(e); }
    }
    updateThemeSel();
  }

  function initThemeSystemWatcher() {
    if (!window.matchMedia || !window.matchMedia.addEventListener) return;
    window.matchMedia('(prefers-color-scheme: dark)')
      .addEventListener('change', () => {
        if (state.theme && state.theme.pref === 'system') applyTheme('system');
      });
  }

  function initSettingsUI() {
    const btn = $('#settings-btn');
    const panel = $('#settings-panel');
    const setOpen = (open) => {
      panel.classList.toggle('hidden', !open);
      panel.classList.toggle('open', open);
      btn.setAttribute('aria-expanded', String(open));
    };
    btn.addEventListener('click', (e) => {
      e.stopPropagation();
      setOpen(panel.classList.contains('hidden'));
    });
    panel.addEventListener('click', (e) => e.stopPropagation());
    document.addEventListener('click', () => setOpen(false));
    renderThemeGrid();
  }

  /* ================= resizable / collapsible panes ================= */

  const LAYOUT_DEFAULTS = { explorerW: 256, casesW: 320, outH: 210, explorer: true, output: true, cases: true };

  function loadLayout() {
    let v = null;
    try { v = JSON.parse(localStorage.getItem(LS_LAYOUT) || 'null'); } catch (e) { v = null; }
    return Object.assign({}, LAYOUT_DEFAULTS, v || {});
  }

  function persistLayout() {
    try { localStorage.setItem(LS_LAYOUT, JSON.stringify(state.layout)); } catch (e) { /* ignore */ }
    persistSettingsFile();
  }

  function applyLayout() {
    const L = state.layout;
    $('#explorer').style.width = (L.explorer ? L.explorerW : 0) + 'px';
    $('#cases').style.width = (L.cases ? L.casesW : 0) + 'px';
    $('#output').style.height = (L.output ? L.outH : 0) + 'px';
    $('#x-split').classList.toggle('hidden', !L.explorer);
    $('#y-split').classList.toggle('hidden', !L.output);
    $('#z-split').classList.toggle('hidden', !L.cases);
    ['explorer', 'output', 'cases'].forEach((n) => {
      const b = $('#vt-' + n);
      if (b) b.setAttribute('aria-pressed', String(L[n]));
    });
  }

  function togglePane(name) {
    const L = state.layout;
    L[name] = !L[name];
    applyLayout();
    persistLayout();
  }

  function bindSplitter(el, kind) {
    if (!el) return;
    el.addEventListener('mousedown', (e) => {
      if (e.button !== 0) return;
      e.preventDefault();
      const body = document.body;
      body.classList.add('resizing');
      el.classList.add('dragging');
      const L = state.layout;
      const sx = e.clientX;
      const sy = e.clientY;
      const explorerW = L.explorerW;
      const casesW = L.casesW;
      const outH = L.outH;
      const pane = kind === 'explorer' ? $('#explorer') : kind === 'cases' ? $('#cases') : $('#output');
      pane.classList.add('no-anim');
      const move = (ev) => {
        if (kind === 'explorer') {
          L.explorerW = Math.max(150, Math.min(explorerW + (ev.clientX - sx), window.innerWidth - 520));
          pane.style.width = L.explorerW + 'px';
        } else if (kind === 'cases') {
          L.casesW = Math.max(230, Math.min(casesW - (ev.clientX - sx), window.innerWidth - 430));
          pane.style.width = L.casesW + 'px';
        } else {
          L.outH = Math.max(90, Math.min(outH + (sy - ev.clientY), window.innerHeight - 160));
          pane.style.height = L.outH + 'px';
        }
      };
      const up = () => {
        pane.classList.remove('no-anim');
        body.classList.remove('resizing');
        el.classList.remove('dragging');
        persistLayout();
        document.removeEventListener('mousemove', move);
        document.removeEventListener('mouseup', up);
      };
      document.addEventListener('mousemove', move);
      document.addEventListener('mouseup', up);
    });
  }

  function initPanelsUI() {
    state.layout = loadLayout();
    applyLayout();
    bindSplitter($('#x-split'), 'explorer');
    bindSplitter($('#z-split'), 'cases');
    bindSplitter($('#y-split'), 'output');
    ['explorer', 'output', 'cases'].forEach((n) => {
      const btn = $('#vt-' + n);
      if (btn) btn.addEventListener('click', () => togglePane(n));
    });
    const col = { '#collapse-explorer': 'explorer', '#collapse-output': 'output', '#collapse-cases': 'cases' };
    for (const id of Object.keys(col)) {
      const b = $(id);
      if (b) b.addEventListener('click', () => togglePane(col[id]));
    }
  }

  /* ============ file-backed settings (~/.algolab-settings) ============
     The backend stores one JSON object per user at ~/.algolab-settings.
     localStorage stays as an instant cache + fallback; the file wins on boot
     (it survives browser-profile resets) and every change is mirrored to it. */

  function lsNum(key) {
    try {
      const v = localStorage.getItem(key);
      if (v === null || v === '') return null;
      const n = Number(v);
      return Number.isNaN(n) ? null : n;
    } catch (e) { return null; }
  }

  function lsBool(key, fallback) {
    try {
      const v = localStorage.getItem(key);
      if (v === null) return fallback;
      return v === '1';
    } catch (e) { return fallback; }
  }

  /** Canonical settings object mirrored to ~/.algolab-settings. */
  function settingsFromState() {
    return {
      theme: storedTheme(),
      layout: (state.layout) ? state.layout : loadLayout(),
      jdkHome: (() => { try { return localStorage.getItem(LS_JDK) || ''; } catch (e) { return ''; } })(),
      level: lsNum(LS_LEVEL),
      lint: state.lintEnabled !== false,
      suggest: state.suggestEnabled !== false,
      vim: lsBool(LS_VIM, false),
      dsaMigrate: lsBool(LS_DSA, false),
    };
  }

  let settingsFileTimer = null;

  /** Debounce writing the settings file so a burst of changes lands as one PUT. */
  function persistSettingsFile() {
    clearTimeout(settingsFileTimer);
    settingsFileTimer = setTimeout(() => { settingsFileTimer = null; pushSettingsFile(); }, 350);
  }

  async function pushSettingsFile() {
    try {
      await api('/api/settings', {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(settingsFromState()),
      });
    } catch (e) { /* non-fatal — the file just misses the latest change */ }
  }

  /** Turn the vim toggle back on/off to match the persisted preference. */
  function applyVimPref(MonacoVim) {
    if (!MonacoVim || !state.editor) return;
    const wantOn = lsBool(LS_VIM, false);
    const isOn = !!state.vim;
    if (wantOn && !isOn) {
      const t = $('#vim-toggle');
      if (t) t.checked = true;
      toggleVim(true, MonacoVim);
    } else if (!wantOn && isOn) {
      const t = $('#vim-toggle');
      if (t) t.checked = false;
      toggleVim(false, MonacoVim);
    }
  }

  /**
   * Pull the settings file into localStorage + live state. Runs before the JDK
   * list / tree load so their localStorage reads already see the file values.
   */
  async function loadFileSettings() {
    let s = null;
    try { s = await api('/api/settings'); } catch (e) { return; }
    if (!s || typeof s !== 'object' || Array.isArray(s)) return;
    try {
      if (typeof s.theme === 'string' && s.theme && themeById(s.theme)) {
        localStorage.setItem(LS_THEME, s.theme);
      }
      if (s.layout && typeof s.layout === 'object') {
        localStorage.setItem(LS_LAYOUT, JSON.stringify(s.layout));
        state.layout = Object.assign({}, LAYOUT_DEFAULTS, s.layout);
        applyLayout();
      }
      if (typeof s.jdkHome === 'string' && s.jdkHome) {
        localStorage.setItem(LS_JDK, s.jdkHome);
      }
      if (s.level !== null && s.level !== undefined && s.level !== '') {
        const n = Number(s.level);
        if (!Number.isNaN(n)) localStorage.setItem(LS_LEVEL, String(n));
      }
      if (typeof s.lint === 'boolean') {
        localStorage.setItem(LS_LINT, s.lint ? '1' : '0');
        state.lintEnabled = s.lint;
        const t = $('#lint-toggle');
        if (t) t.checked = s.lint;
        if (!s.lint) clearMarkers();
      }
      if (typeof s.suggest === 'boolean') {
        localStorage.setItem(LS_SUGGEST, s.suggest ? '1' : '0');
        state.suggestEnabled = s.suggest;
        const t = $('#suggest-toggle');
        if (t) t.checked = s.suggest;
      }
      if (typeof s.vim === 'boolean') {
        localStorage.setItem(LS_VIM, s.vim ? '1' : '0');
        if (state.editorReady) applyVimPref(state.monacoVim);
      }
      if (s.dsaMigrate === true) {
        localStorage.setItem(LS_DSA, '1');
      } else if (s.dsaMigrate === false) {
        localStorage.removeItem(LS_DSA);
      }
    } catch (e) { /* a bad value should not stop the rest of the load */ }
    applyTheme(storedTheme());
  }

  /* ================= boot ================= */

  function bindStaticEvents() {
    $('#refresh-tree').addEventListener('click', refreshTree);
    $('#add-case').addEventListener('click', addCase);

    $('#jdk-select').addEventListener('change', onJdkChange);
    $('#java-level-select').addEventListener('change', onLevelChange);

    // right-click on empty tree space → create at root / refresh
    $('#tree').addEventListener('contextmenu', (e) => {
      if (e.target !== $('#tree')) return;
      e.preventDefault();
      showContextMenu(e.clientX, e.clientY, [
        { label: 'New .algolab File…', action: () => promptCreate('', false, '.algolab') },
        { label: 'New File…', action: () => promptCreate('', false) },
        { label: 'New Folder…', action: () => promptCreate('', true) },
        '---',
        { label: 'Copy Path', action: () => copyPath('') },
        { label: 'Refresh', action: refreshTree },
      ]);
    });

    // dismiss the context menu
    window.addEventListener('click', hideContextMenu);
    window.addEventListener('blur', hideContextMenu);
    window.addEventListener('resize', hideContextMenu);
    window.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') hideContextMenu();
      const mod = e.metaKey || e.ctrlKey;
      if (!mod) return;
      const key = e.key.toLowerCase();
      if (key === 's') { e.preventDefault(); saveFile(); }
      else if (key === 'enter') { e.preventDefault(); runAll(); }
    });
    $('#tree').addEventListener('scroll', hideContextMenu);
  }

  async function init() {
    bindStaticEvents();
    state.theme = resolveTheme(storedTheme());
    initPanelsUI();
    initSettingsUI();
    initThemeSystemWatcher();
    applyTheme(state.theme.pref);
    try {
      const health = await api('/api/health');
      state.root = health.root;
      state.rootName = health.rootName;
      $('#root-label').textContent = health.root;
      $('#status-left').textContent = 'root: ' + health.root;
    } catch (e) {
      $('#status-left').textContent = 'Cannot reach backend: ' + e.message;
      $('#tree').appendChild(el('p', 'px-3 py-2 text-xs text-red-400',
        'Backend unreachable — is the server running?'));
      return;
    }
    // File-backed settings (~/.algolab-settings) override the localStorage cache;
    // loaded before the JDK list / tree so their localStorage reads see the values.
    await loadFileSettings();
    state.expanded.add(''); // root expanded by default
    await Promise.all([renderTree(), loadJdks()]);
    // Seed/refresh the settings file so it exists even before the first change.
    pushSettingsFile();
  }

  window.addEventListener('DOMContentLoaded', init);

  // Monaco + monaco-vim load async (all from local files); Vim is optional.
  require(['vs/editor/editor.main'], function () {
    registerJavaLanguageOnce();
    require(['monaco-vim'], function (MonacoVim) {
      initEditor(MonacoVim);
    }, function (vimErr) {
      console.warn('MonacoVim failed to load, falling back to standard editor:', vimErr);
      initEditor(null);
    });
    // Tree-sitter contextual highlighting (async; safe to fire whenever — if it
    // loads after a file is open the registry hot-swap repaints automatically).
    if (window.AlgoLabTS) window.AlgoLabTS.start();
  }, function (err) {
    console.error('Failed to load Monaco Editor:', err);
    $('#status-right').textContent = 'Editor failed to load';
  });
})();
