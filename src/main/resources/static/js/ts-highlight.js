/**
 * AlgoLab tree-sitter syntax highlighting.
 *
 * This vendored Monaco build ships a native tree-sitter tokenization path:
 * when a tokenization support is registered for a language in the internal
 * `TreeSitterTokenizationRegistry`, every model of that language hot-swaps to
 * `TreeSitterTokens`, which calls `support.tokenizeEncoded(lineNumber, model)`
 * whenever a line needs to be painted. That lets us color from a *whole-file*
 * parse, so tokens are contextual (method decl vs call, fields vs locals,
 * generics, javadoc, ...) instead of regex-guessed.
 *
 * We vendor web-tree-sitter + the tree-sitter-java grammar wasm (fully
 * offline) and translate syntax-tree nodes into rich dotted token scopes that
 * the AlgoLab themes color (see themes.js / the semantic rules in app.js).
 */
(function () {
  'use strict';

  var SRC = (document.currentScript && document.currentScript.src) || '';
  var DIR = SRC.replace(/[^/]*$/, '');
  var WASM_BASE = DIR + '../vendor/tree-sitter/';

  /* ------------------------------ tables ------------------------------ */

  var NUMBER_KINDS = [
    'decimal_integer_literal', 'hex_integer_literal', 'octal_integer_literal',
    'binary_integer_literal', 'decimal_floating_point_literal', 'hex_floating_point_literal',
  ];

  var MODIFIER_KW = new Set([
    'public', 'protected', 'private', 'static', 'final', 'abstract', 'synchronized',
    'native', 'strictfp', 'transient', 'volatile', 'default', 'sealed', 'non-sealed',
  ]);
  var LITERAL_KW = new Set(['true', 'false', 'null']);

  /* ------------------------------ state ------------------------------ */

  var state = { ready: false, registry: null, themeService: null, langId: 1, disposable: null };
  var parser = null;
  var cache = null;            // { model, version, text, root, lines }
  var pendingRefresh = new Set();

  window.AlgoLabTS = {
    isReady: function () { return state.ready; },
    start: function () { return boot(); },

    /**
     * Whole-file parse for the given model (same cache the highlighter uses).
     * Returns { root, text } for the model's current version, or null when
     * tree-sitter isn't ready, the model is gone, or the file is too large.
     * Used by live syntax diagnostics and the autocomplete symbol engine.
     */
    tree: function (model) {
      if (!state.ready || !model) return null;
      try {
        var parsed = ensureParsed(model);
        return (parsed && parsed.root) ? { root: parsed.root, text: parsed.text } : null;
      } catch (e) {
        return null;
      }
    },
  };

  /* ------------------------------ boot ------------------------------ */

  var bootPromise = null;

  function boot() {
    if (!bootPromise) {
      bootPromise = bootInner().catch(function (e) {
        console.warn('AlgoLab tree-sitter disabled:', e);
      });
    }
    return bootPromise;
  }

  async function bootInner() {
    if (!window.monaco || !monaco.languages || !window.require) return;

    var TS;
    try {
      TS = await import(/* webpackIgnore: true */ WASM_BASE + 'web-tree-sitter.js');
    } catch (e) {
      throw new Error('web-tree-sitter import: ' + e);
    }

    var res = await fetch(WASM_BASE + 'tree-sitter-java.wasm');
    if (!res.ok) throw new Error('fetch tree-sitter-java.wasm: HTTP ' + res.status);
    var wasmBytes = new Uint8Array(await res.arrayBuffer());

    await TS.Parser.init({ locateFile: function (f) { return WASM_BASE + f; } });
    var Java = await TS.Language.load(wasmBytes);
    parser = new TS.Parser();
    parser.setLanguage(Java);

    var langMod = await amd('vs/editor/common/languages');
    var services = await amd('vs/editor/standalone/browser/standaloneServices');
    var themeMod = await amd('vs/editor/standalone/common/standaloneTheme');
    if (!langMod || !langMod.TreeSitterTokenizationRegistry || !services || !themeMod) {
      throw new Error('monaco tree-sitter internals unavailable');
    }

    state.registry = langMod.TreeSitterTokenizationRegistry;
    state.themeService = services.StandaloneServices.get(themeMod.IStandaloneThemeService);
    state.langId = (monaco.languages.getEncodedLanguageId)
      ? monaco.languages.getEncodedLanguageId('java') : 1;

    state.disposable = state.registry.register('java', {
      tokenizeEncoded: function (lineNumber, model) { return tokensForLine(lineNumber, model); },
    });
    state.ready = true;
    cache = null;
  }

  function amd(name) {
    return new Promise(function (resolve) {
      var r = (typeof window.require === 'function') ? window.require : null;
      if (!r) return resolve(null);
      try {
        r([name], function (m) { resolve(m); }, function () { resolve(null); });
      } catch (e) { resolve(null); }
    });
  }

  /* --------------------------- tokenization --------------------------- */

  /**
   * Contract of this custom Monaco build: `TreeSitterTokens.getLineTokens`
   * wraps the returned Uint32Array straight into `LineTokens`, whose flat
   * pairs are (END offset, metadata) with an implicit start at 0 and NO tail
   * padding. So we must tile the whole line [0, lineLength] with default
   * metadata wherever no span applies, and the final end must equal the line
   * length exactly — otherwise text after the last token is never painted.
   */
  function tokensForLine(lineNumber, model) {
    var lineLen = model ? model.getLineLength(lineNumber) : 0;
    if (!state.ready || !model) return defaultLine(lineLen);
    var parsed;
    try { parsed = ensureParsed(model); } catch (e) { return defaultLine(lineLen); }
    var spans = (parsed.lines && parsed.lines[lineNumber - 1]) || null;
    if (!spans || !spans.length) return defaultLine(lineLen);

    var tt, langId = state.langId, defMeta = 0;
    try {
      tt = state.themeService.getColorTheme().tokenTheme;
      defMeta = tt.match(langId, '__algolab_default__') | 1024;
    } catch (e) { return defaultLine(lineLen); }

    var arr = [];
    var cursor = 0;   // end offset of the last emitted token
    var lastMeta = -1;
    for (var i = 0; i < spans.length; i++) {
      var sp = spans[i];
      var s = sp.s < 0 ? 0 : sp.s; if (s > lineLen) s = lineLen;
      var e = sp.e < 0 ? 0 : sp.e; if (e > lineLen) e = lineLen;
      if (e <= s || e <= cursor) continue; // empty / overlapping / out of order
      if (s > cursor) {
        // gap (whitespace / unpainted syntax): fill with a default token [cursor, s)
        arr.push(s, defMeta);
        lastMeta = defMeta;
      }
      var meta = tt.match(langId, sp.scope) | 1024;
      if (meta === lastMeta) {
        // adjacent same-color run: extend the previous token to cover [cursor, e)
        arr[arr.length - 2] = e;
      } else {
        arr.push(e, meta);
        lastMeta = meta;
      }
      cursor = e;
    }
    if (cursor < lineLen) {
      arr.push(lineLen, defMeta); // trailing default so the line is fully tiled
    }
    if (!arr.length) arr.push(lineLen, defMeta);
    return new Uint32Array(arr);
  }

  function defaultLine(lineLen) {
    var meta = 0;
    try {
      meta = state.themeService.getColorTheme().tokenTheme.match(state.langId, '__algolab_default__') | 1024;
    } catch (e) { /* leave default */ }
    return new Uint32Array([lineLen, meta]);
  }

  function ensureParsed(model) {
    if (cache && cache.model === model && cache.version === model.getVersionId()) return cache;
    var text = model.getValue();
    var version = model.getVersionId();
    if (text.length > 300000) {
      cache = { model: model, version: version, text: text, root: null, lines: null };
      return cache;
    }
    var root = parser.parse(text).rootNode;
    cache = { model: model, version: version, text: text, root: root, lines: buildLineTokens(text, root) };
    scheduleRefresh(model);
    return cache;
  }

  /** After a (re)parse, ask Monaco to repaint every visible line so edits that
      change context elsewhere (block comments, string boundaries) recolor. */
  function scheduleRefresh(model) {
    if (pendingRefresh.has(model)) return;
    pendingRefresh.add(model);
    setTimeout(function () {
      pendingRefresh.delete(model);
      if (!model || model.isDisposed()) return;
      try {
        var tok = model.tokenization;
        if (tok && typeof tok.resetTokenization === 'function') { tok.resetTokenization(); return; }
      } catch (e) { /* try next */ }
      try {
        var tok2 = model.tokenization;
        if (tok2 && typeof tok2.forceTokenization === 'function') {
          var n = model.getLineCount();
          for (var i = 1; i <= n; i++) tok2.forceTokenization(i);
          return;
        }
      } catch (e) { /* try next */ }
      try {
        if (window.monaco && monaco.editor && monaco.editor.setModelLanguage) {
          var l = model.getLanguageId();
          monaco.editor.setModelLanguage(model, 'plaintext');
          monaco.editor.setModelLanguage(model, l);
        }
      } catch (e) { /* give up */ }
    }, 0);
  }

  /* ----------------- tree -> per-line scope spans ----------------- */

  function buildLineTokens(text, root) {
    var b2u = byteToUtf16Map(text);
    var rowStarts = [0];
    for (var i = 0; i < text.length; i++) if (text.charCodeAt(i) === 10) rowStarts.push(i + 1);
    var lineUtf16 = rowStarts.map(function (bs) { return b2u[bs]; });
    var totalBytes = b2u.totalBytes;

    var lines = [];
    var row = 0;

    function rowOf(bs) {
      while (row + 1 < rowStarts.length && rowStarts[row + 1] <= bs) row++;
      return row;
    }
    function push(r, bs, be, scope) {
      if (be <= bs) return;
      if (!lines[r]) lines[r] = [];
      var s = b2u[bs] - lineUtf16[r];
      var e = b2u[be] - lineUtf16[r];
      if (e > s) lines[r].push({ s: s, e: e, scope: scope });
    }
    // Emit a node across every line it covers (multi-line comments/strings),
    // clamped to each line's byte range so columns never exceed a row.
    function pushAcross(node, scope) {
      var bs = node.startIndex, be = node.endIndex;
      var r0 = rowOf(bs);
      var r1 = rowOf(Math.max(bs, be - 1));
      for (var r = r0; r <= r1; r++) {
        var rowEndByte = (r + 1 < rowStarts.length) ? rowStarts[r + 1] : totalBytes;
        var segS = Math.max(bs, rowStarts[r]);
        var segE = Math.min(be, rowEndByte);
        push(r, segS, segE, scope);
      }
    }

    // Iterative pre-order walk. Container nodes that paint their whole range
    // (comments, strings, numbers, ...) skip their descendants.
    var stack = [root];
    while (stack.length) {
      var node = stack.pop();
      var scope = classify(node, text);
      if (scope) {
        pushAcross(node, scope);
        if (isWholeRange(node.type)) continue;
      }
      var kids = node.children;
      for (var k = kids.length - 1; k >= 0; k--) stack.push(kids[k]);
    }
    for (var li = 0; li < lines.length; li++) {
      if (lines[li] && lines[li].length > 1) {
        lines[li].sort(function (a, b) { return a.s - b.s; });
      }
    }
    return lines;
  }

  function isWholeRange(t) {
    return t === 'string_literal' || t === 'text_block' || t === 'character_literal' ||
      t === 'comment' || t === 'line_comment' || t === 'block_comment' ||
      t === 'javadoc_comment' || NUMBER_KINDS.indexOf(t) >= 0;
  }

  function byteToUtf16Map(text) {
    var map = [];
    var bytes = 0;
    var i = 0;
    while (i < text.length) {
      var cp = text.codePointAt(i);
      var len = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
      for (var b = 0; b < len; b++) map[bytes + b] = i;
      bytes += len;
      i += cp > 0xffff ? 2 : 1;
    }
    map[bytes] = text.length;
    map.totalBytes = bytes;
    return map;
  }

  /* -------------------------- classification -------------------------- */

  function classify(node, text) {
    var t = node.type;

    if (t === 'comment' || t === 'line_comment' || t === 'block_comment' || t === 'javadoc_comment') return 'comment';
    if (t === 'string_literal' || t === 'text_block' || t === 'character_literal') return 'string';
    if (t === 'escape_sequence') return 'string.escape';
    if (NUMBER_KINDS.indexOf(t) >= 0) return 'number';
    if (t === 'integral_type' || t === 'floating_point_type' || t === 'boolean_type' || t === 'void_type') return 'type.primitive';
    if (t === 'type_identifier') return nearAnnotation(node) ? 'annotation' : 'type';
    if (t === 'identifier') return classifyIdentifier(node, text);

    // anonymous leaves: keywords carry their own text as the node type
    if (!node.isNamed && /^[A-Za-z_][A-Za-z0-9_]*$/.test(t)) {
      if (MODIFIER_KW.has(t)) return 'keyword.modifier';
      if (LITERAL_KW.has(t)) return 'constant.language';
      return 'keyword.control';
    }
    return null;
  }

  function classifyIdentifier(node, text) {
    var p = node.parent;
    if (!p) return 'variable';
    var pt = p.type;

    // declarations
    if (pt === 'method_declaration') return 'function.method';
    if (pt === 'constructor_declaration') return 'function.constructor';
    if (pt === 'class_declaration' || pt === 'interface_declaration' ||
        pt === 'enum_declaration' || pt === 'record_declaration' ||
        pt === 'annotation_type_declaration') return 'type';

    // member access / calls: System.out.print(..)
    if (pt === 'method_invocation' || pt === 'field_access') {
      // NOTE: tree-sitter JS bindings mint fresh wrapper objects per access, so
      // compare nodes by .id, never by reference (kids.indexOf(node) fails).
      var kids = p.children;
      var idx = -1;
      for (var i = 0; i < kids.length; i++) {
        if (kids[i].id === node.id) { idx = i; break; }
      }
      var next = idx >= 0 ? kids[idx + 1] : null;
      var isMember = (pt === 'field_access') && kids.length > 0 && kids[kids.length - 1].id === node.id;
      if (pt === 'method_invocation' && next && next.type === 'argument_list') {
        return 'function.call'; // method name
      }
      if (isMember) return 'property';       // .out / .length / .field
      if (next && next.type === '.') {
        // object side of a dotted chain: class-like (System.out) or instance
        return (/^[A-Z]/).test(text.slice(node.startIndex, node.endIndex)) ? 'type' : variableRole(node, text);
      }
      if (pt === 'method_invocation') return variableRole(node, text); // plain object: sc.nextInt()
      return variableRole(node, text);
    }

    // declarators (local/field/param declarations)
    if (pt === 'variable_declarator' || pt === 'variable_declarator_id') {
      if (isConstantDecl(node, text)) return 'constant';
      var decl = nearestDeclRoot(p);
      if (decl === 'formal_parameter' || decl === 'catch_formal_parameter' ||
          decl === 'lambda_expression' || decl === 'resource') return 'variable.parameter';
      if (decl === 'field_declaration') return 'variable.field';
      return 'variable.local';
    }

    if (pt === 'formal_parameter' || pt === 'catch_formal_parameter' ||
        pt === 'lambda_expression') return 'variable.parameter';
    if (pt === 'enhanced_for_statement') return 'variable.local';

    if (nearAnnotation(node)) return 'annotation';

    if (hasAncestor(p, 'import_declaration')) return importRole(p, text, node);
    if (hasAncestor(p, 'package_declaration')) return 'namespace';

    return variableRole(node, text);
  }

  function nearestDeclRoot(declarator) {
    var p = declarator.parent;
    while (p && p.parent) {
      var tp = p.type;
      if (tp === 'field_declaration' || tp === 'local_variable_declaration' ||
          tp === 'formal_parameter' || tp === 'catch_formal_parameter' ||
          tp === 'lambda_expression' || tp === 'resource' ||
          tp === 'enhanced_for_statement' || tp === 'for_statement') {
        return tp;
      }
      p = p.parent;
    }
    return 'variable';
  }

  function isConstantDecl(node, text) {
    var name = text.slice(node.startIndex, node.endIndex);
    if (!/^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$/.test(name)) return false;
    var p = node.parent;
    while (p && p.parent && p.type !== 'field_declaration' && p.type !== 'local_variable_declaration') {
      if (p.type === 'enum_constant') return true;
      p = p.parent;
    }
    if (!p || !p.parent) return false;
    // final + uppercase => constant (covers static final class constants too)
    var head = p.namedChildren[0];
    if (head && head.type === 'modifiers') {
      for (var i = 0; i < head.children.length; i++) {
        if (head.children[i].type === 'final') return true;
      }
    }
    return false;
  }

  function variableRole(node, text) {
    var name = text.slice(node.startIndex, node.endIndex);
    if (name.length > 1 && /^[A-Z][A-Z0-9]*(_[A-Z0-9]+)*$/.test(name)) return 'constant';
    return 'variable';
  }

  function importRole(top, text, node) {
    // last dotted segment = imported class (type); everything before = namespace
    var idents = [];
    var stack = [top];
    while (stack.length) {
      var n = stack.pop();
      if (n.type === 'identifier' || n.type === 'type_identifier') idents.push(n);
      var ch = n.children;
      for (var i = ch.length - 1; i >= 0; i--) stack.push(ch[i]);
    }
    if (!idents.length) return 'namespace';
    var last = idents[idents.length - 1];
    var hasStar = text.slice(top.endIndex - 1, top.endIndex) === '*';
    if (hasStar) return 'namespace';
    return last === node ? 'type' : 'namespace';
  }

  function hasAncestor(node, type) {
    var p = node;
    while (p) {
      if (p.type === type) return true;
      p = p.parent;
    }
    return false;
  }

  function nearAnnotation(node) {
    var p = node.parent;
    var g = p && p.parent;
    return p && (p.type === 'annotation' || p.type === 'marker_annotation' ||
      p.type === 'annotation_type_declaration') ||
      g && (g.type === 'annotation' || g.type === 'marker_annotation' ||
      g.type === 'annotation_type_declaration');
  }
})();
