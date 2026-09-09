// Temporary UI verification: drives headless Chrome over the DevTools Protocol
// using only Node built-ins (fetch + WebSocket). Removed after the check.
import { spawn } from 'node:child_process';
import fs from 'node:fs';

const CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const DEBUG_PORT = 9333;
const APP_URL = 'http://localhost:18080/';
const PROFILE = '/tmp/dsa-chrome-profile';

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
fs.rmSync(PROFILE, { recursive: true, force: true });

const chrome = spawn(CHROME, [
  '--headless=new',
  `--remote-debugging-port=${DEBUG_PORT}`,
  `--user-data-dir=${PROFILE}`,
  '--no-first-run', '--no-default-browser-check', '--disable-gpu',
  '--window-size=1680,1050',
  'about:blank',
], { stdio: 'ignore' });

let target = null;
for (let i = 0; i < 40 && !target; i++) {
  try {
    const res = await fetch(`http://127.0.0.1:${DEBUG_PORT}/json/new?${encodeURIComponent(APP_URL)}`, { method: 'PUT' });
    if (res.ok) target = await res.json();
  } catch { /* retry */ }
  if (!target) await sleep(250);
}
if (!target) {
  console.error('FAIL: could not create Chrome target');
  chrome.kill();
  process.exit(1);
}

const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('ws error')); });

let msgId = 0;
const pending = new Map();
const consoleErrors = [];
const exceptions = [];

ws.onmessage = (ev) => {
  const msg = JSON.parse(ev.data);
  if (msg.id && pending.has(msg.id)) {
    const p = pending.get(msg.id);
    pending.delete(msg.id);
    msg.error ? p.reject(new Error(JSON.stringify(msg.error))) : p.resolve(msg.result);
  } else if (msg.method === 'Runtime.consoleAPICalled') {
    const text = (msg.params.args || []).map((a) => (a.value !== undefined ? a.value : a.description) ?? '').join(' ');
    if (msg.params.type === 'error') consoleErrors.push(text);
  } else if (msg.method === 'Runtime.exceptionThrown') {
    exceptions.push(msg.params.exceptionDetails?.exception?.description || msg.params.exceptionDetails?.text || 'unknown');
  }
};

const send = (method, params = {}) => new Promise((resolve, reject) => {
  const id = ++msgId;
  pending.set(id, { resolve, reject });
  ws.send(JSON.stringify({ id, method, params }));
});

const evalJs = async (expression) => {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error('eval failed: ' + JSON.stringify(r.exceptionDetails.exception?.description || r.exceptionDetails.text));
  return r.result.value;
};

const waitFor = async (expression, timeoutMs = 10000) => {
  const start = Date.now();
  while (Date.now() - start < timeoutMs) {
    if (await evalJs(expression)) return true;
    await sleep(200);
  }
  return false;
};

await send('Runtime.enable');
await send('Page.enable');

const checks = [];
const check = (name, ok, extra = '') => checks.push({ name, ok, extra });

// ---------- file tree ----------
if (!(await waitFor(`document.querySelectorAll('#tree .tree-row').length >= 2`))) {
  check('tree rendered', false, 'rows: ' + await evalJs(`document.querySelectorAll('#tree .tree-row').length`));
} else {
  check('tree rendered', true);
  check('samples dir in tree', await evalJs(`document.querySelector('#tree').textContent.includes('samples')`));
  await evalJs(`[...document.querySelectorAll('#tree .tree-row')].find((r) => r.textContent.includes('samples'))?.click()`);
  if (!(await waitFor(`document.querySelector('#tree').textContent.includes('merge-sort.dsa')`))) {
    check('samples expands', false);
  } else {
    check('samples expands', true);
    check('non-dsa file listed', await evalJs(`document.querySelector('#tree').textContent.includes('notes.txt')`));
    check('non-dsa file locked', await evalJs(
      `[...document.querySelectorAll('#tree .tree-row')].find((r) => r.textContent.includes('notes.txt'))?.classList.contains('opacity-40')`));
  }
}

// ---------- open the .dsa file ----------
await evalJs(`[...document.querySelectorAll('#tree .tree-row')].find((r) => r.textContent.includes('merge-sort.dsa'))?.click()`);
check('file opens (name shown)', await waitFor(`document.querySelector('#file-name').textContent === 'merge-sort.dsa'`));
check('monaco editor rendered', await evalJs(`!!document.querySelector('.monaco-editor')`));
check('editor shows java code', await evalJs(`(document.querySelector('.view-lines')?.textContent || '').includes('public class Main')`));
check('welcome overlay hidden', await evalJs(`document.querySelector('#welcome').classList.contains('hidden')`));

// ---------- test cases ----------
check('3 case tabs', await evalJs(`document.querySelectorAll('#case-tabs > div').length`) === 3);
check('name input present', await evalJs(`!!document.querySelector('#case-body input.input-dark')`));
check('stdin + expected textareas', await evalJs(`document.querySelectorAll('#case-body textarea').length`) === 2);
await evalJs(`document.querySelector('#case-body input[type=checkbox]').click()`);
check('expected textarea hides when unchecked', await evalJs(`document.querySelectorAll('#case-body textarea').length`) === 1);
await evalJs(`document.querySelector('#case-body input[type=checkbox]').click()`);
check('expected textarea shows when checked', await evalJs(`document.querySelectorAll('#case-body textarea').length`) === 2);

// ---------- output pane ----------
check('3 output tabs', await evalJs(`document.querySelectorAll('#output-tabs > div').length`) === 3);
check('output empty hint', await evalJs(`document.querySelector('#output-body').textContent.includes('No output yet')`));

// ---------- vim mode ----------
await evalJs(`document.querySelector('#vim-toggle').click()`);
check('vim status visible', await waitFor(`!document.querySelector('#vim-status').classList.contains('hidden')`));
check('vim starts in NORMAL', (await evalJs(`document.querySelector('#vim-status').textContent`)).includes('NORMAL'));

await evalJs(`document.querySelector('.monaco-editor textarea').focus()`);
const key = async (k, code, vk) => {
  await send('Input.dispatchKeyEvent', { type: 'keyDown', key: k, code, windowsVirtualKeyCode: vk, nativeVirtualKeyCode: vk });
  await send('Input.dispatchKeyEvent', { type: 'char', text: k, key: k, code, windowsVirtualKeyCode: vk });
  await send('Input.dispatchKeyEvent', { type: 'keyUp', key: k, code, windowsVirtualKeyCode: vk });
};
await key('i', 'KeyI', 73);
check('vim enters INSERT', await waitFor(`document.querySelector('#vim-status').textContent.includes('INSERT')`));
await key('a', 'KeyA', 65);
await key('b', 'KeyB', 66);
await key('c', 'KeyC', 67);
check('typing inserts into editor', await waitFor(`(document.querySelector('.view-lines')?.textContent || '').includes('abc')`));
check('dirty dot appears', await evalJs(`!document.querySelector('#dirty-dot').classList.contains('hidden')`));
await send('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27, nativeVirtualKeyCode: 27 });
await send('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Escape', code: 'Escape', windowsVirtualKeyCode: 27, nativeVirtualKeyCode: 27 });
check('vim returns to NORMAL', await waitFor(`document.querySelector('#vim-status').textContent.includes('NORMAL')`));

// ---------- run (stub) ----------
await evalJs(`document.querySelector('#run-btn').click()`);
check('run shows stub error', await waitFor(`document.querySelector('#output-body').textContent.includes('Runner not implemented')`));
check('output tab shows ✗', await evalJs(`document.querySelector('#output-tabs > div:first-child span')?.textContent === '✗'`));

// ---------- layout metrics ----------
const layout = await evalJs(`(() => {
  const rect = (s) => { const r = document.querySelector(s)?.getBoundingClientRect(); return r ? { w: Math.round(r.width), h: Math.round(r.height), x: Math.round(r.x) } : null; };
  return { explorer: rect('aside'), editor: rect('#editor'), output: rect('#output'), cases: rect('#case-body') };
})()`);
console.log('layout:', JSON.stringify(layout));
check('explorer ~256px wide', layout.explorer && layout.explorer.w === 256);
check('editor pane present', layout.editor && layout.editor.w > 500 && layout.editor.h > 200);
check('output pane spans editor width', layout.output && layout.output.w > 500 && layout.output.h >= 200);
check('cases pane ~320px wide', layout.cases && layout.cases.w === 320);

// ---------- screenshots ----------
const shot = await send('Page.captureScreenshot', { format: 'png' });
fs.writeFileSync('/tmp/dsa-ui-loaded.png', Buffer.from(shot.data, 'base64'));
console.log('screenshots: /tmp/dsa-ui-loaded.png (vim on, after run)');

// ---------- report ----------
console.log('\n===== RESULTS =====');
let failed = 0;
for (const c of checks) {
  console.log(`${c.ok ? 'PASS' : 'FAIL'}  ${c.name}${c.extra ? '  (' + c.extra + ')' : ''}`);
  if (!c.ok) failed++;
}
if (consoleErrors.length) {
  console.log('\n-- console errors --');
  consoleErrors.forEach((e) => console.log(e));
  failed++;
}
if (exceptions.length) {
  console.log('\n-- uncaught exceptions --');
  exceptions.forEach((e) => console.log(e));
  failed++;
}
ws.close();
chrome.kill();
process.exit(failed ? 1 : 0);