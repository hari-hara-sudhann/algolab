/**
 * AlgoLab Java autocomplete engine (client-side).
 *
 * Builds on the whole-file tree-sitter parse that the highlighter already keeps
 * (window.AlgoLabTS.tree(model)) to answer two questions at the cursor:
 *
 *   1. "What is the receiver?" — `Arrays.`, `sc.`, `arr.`, `n.` where `sc` is a
 *      declared Scanner, `arr` an int[], and `n` a user-declared class in this
 *      file. Chains like `System.out.` resolve through known members.
 *   2. "Which members does that type offer?" — curated JDK tables for the DSA
 *      vocabulary plus methods/fields of same-file user classes.
 *
 * When the receiver can't be resolved it returns nothing, letting the plain
 * snippet/keyword provider in app.js handle the request. All inference is
 * deliberately shallow: no generics-aware members, no inference through method
 * return values beyond the few known hops, and wildcard java.util imports are
 * assumed.
 */
(function () {
  'use strict';

  const LS_SUGGEST = 'dsa.suggest';

  /* ------------------------------------------------------------------ *
   * Curated member tables: key = declared type name (generic suffix is
   * stripped before lookup). Entry = [name, params, returnType].
   * params is shown in the detail signature; insertion always uses
   * name + ($0) (or name + () for zero-arg members).
   * ------------------------------------------------------------------ */

  const M = (name, params, ret) => [name, params, ret];

  const MEMBERS = {
    String: [
      M('length', '()', 'int'),
      M('charAt', '(int index)', 'char'),
      M('substring', '(int beginIndex)', 'String'),
      M('substring', '(int beginIndex, int endIndex)', 'String'),
      M('indexOf', '(String s)', 'int'),
      M('indexOf', '(int ch)', 'int'),
      M('lastIndexOf', '(String s)', 'int'),
      M('startsWith', '(String prefix)', 'boolean'),
      M('endsWith', '(String suffix)', 'boolean'),
      M('contains', '(CharSequence s)', 'boolean'),
      M('equals', '(Object o)', 'boolean'),
      M('equalsIgnoreCase', '(String other)', 'boolean'),
      M('compareTo', '(String other)', 'int'),
      M('trim', '()', 'String'),
      M('toLowerCase', '()', 'String'),
      M('toUpperCase', '()', 'String'),
      M('toCharArray', '()', 'char[]'),
      M('split', '(String regex)', 'String[]'),
      M('isEmpty', '()', 'boolean'),
      M('replace', '(CharSequence a, CharSequence b)', 'String'),
      M('format', '(String fmt, Object... args)', 'String'),
      M('join', '(CharSequence delimiter, CharSequence... elems)', 'String'),
    ],
    StringBuilder: [
      M('append', '(Object o)', 'StringBuilder'),
      M('append', '(int i)', 'StringBuilder'),
      M('append', '(String s)', 'StringBuilder'),
      M('append', '(char c)', 'StringBuilder'),
      M('insert', '(int offset, Object o)', 'StringBuilder'),
      M('delete', '(int start, int end)', 'StringBuilder'),
      M('reverse', '()', 'StringBuilder'),
      M('toString', '()', 'String'),
      M('length', '()', 'int'),
      M('charAt', '(int index)', 'char'),
      M('setCharAt', '(int index, char ch)', 'void'),
      M('deleteCharAt', '(int index)', 'StringBuilder'),
    ],
    PrintStream: [
      M('println', '()'),
      M('println', '(Object x)'),
      M('println', '(int x)'),
      M('println', '(String x)'),
      M('println', '(char[] x)'),
      M('print', '(Object x)'),
      M('print', '(int x)'),
      M('print', '(String x)'),
      M('printf', '(String fmt, Object... args)', 'PrintStream'),
      M('format', '(String fmt, Object... args)', 'PrintStream'),
      M('flush', '()'),
      M('close', '()'),
    ],
    Scanner: [
      M('nextInt', '()', 'int'),
      M('nextLong', '()', 'long'),
      M('nextDouble', '()', 'double'),
      M('nextFloat', '()', 'float'),
      M('next', '()', 'String'),
      M('nextLine', '()', 'String'),
      M('hasNext', '()', 'boolean'),
      M('hasNextInt', '()', 'boolean'),
      M('hasNextLong', '()', 'boolean'),
      M('hasNextDouble', '()', 'boolean'),
      M('close', '()'),
      M('useDelimiter', '(String pattern)', 'Scanner'),
    ],
    Math: [
      M('max', '(int a, int b)', 'int'),
      M('min', '(int a, int b)', 'int'),
      M('abs', '(int a)', 'int'),
      M('pow', '(double a, double b)', 'double'),
      M('sqrt', '(double a)', 'double'),
      M('floor', '(double a)', 'double'),
      M('ceil', '(double a)', 'double'),
      M('round', '(double a)', 'long'),
      M('random', '()', 'double'),
      M('log', '(double a)', 'double'),
      M('log10', '(double a)', 'double'),
      M('exp', '(double a)', 'double'),
      M('sin', '(double a)', 'double'),
      M('cos', '(double a)', 'double'),
      M('signum', '(double d)', 'double'),
    ],
    Arrays: [
      M('sort', '(int[] a)'),
      M('sort', '(Object[] a)'),
      M('sort', '(int[] a, int from, int to)'),
      M('toString', '(int[] a)', 'String'),
      M('toString', '(Object[] a)', 'String'),
      M('binarySearch', '(int[] a, int key)', 'int'),
      M('binarySearch', '(Object[] a, Object key)', 'int'),
      M('copyOf', '(int[] original, int newLength)', 'int[]'),
      M('copyOf', '(Object[] original, int newLength)', 'Object[]'),
      M('copyOfRange', '(int[] a, int from, int to)', 'int[]'),
      M('fill', '(int[] a, int val)', 'void'),
      M('fill', '(int[] a, int from, int to, int val)', 'void'),
      M('equals', '(int[] a, int[] b)', 'boolean'),
      M('asList', '(T... a)', 'List'),
    ],
    Collections: [
      M('sort', '(List list)'),
      M('sort', '(List list, Comparator c)'),
      M('reverse', '(List list)'),
      M('shuffle', '(List list)'),
      M('binarySearch', '(List list, Object key)', 'int'),
      M('max', '(Collection c)', 'Object'),
      M('min', '(Collection c)', 'Object'),
      M('fill', '(List list, Object obj)', 'void'),
      M('frequency', '(Collection c, Object o)', 'int'),
      M('addAll', '(Collection c, Object... elems)', 'boolean'),
      M('copy', '(List dest, List src)', 'void'),
      M('swap', '(List list, int i, int j)', 'void'),
    ],
    Integer: [
      M('parseInt', '(String s)', 'int'),
      M('parseInt', '(String s, int radix)', 'int'),
      M('valueOf', '(int i)', 'Integer'),
      M('valueOf', '(String s)', 'Integer'),
      M('toString', '(int i)', 'String'),
      M('compare', '(int x, int y)', 'int'),
      M('max', '(int a, int b)', 'int'),
      M('min', '(int a, int b)', 'int'),
      M('bitCount', '(int i)', 'int'),
      M('toBinaryString', '(int i)', 'String'),
      M('intValue', '()', 'int'),
      M('toString', '()', 'String'),
      M('equals', '(Object o)', 'boolean'),
    ],
    Long: [
      M('parseLong', '(String s)', 'long'),
      M('valueOf', '(long l)', 'Long'),
      M('longValue', '()', 'long'),
      M('toString', '()', 'String'),
    ],
    Double: [
      M('parseDouble', '(String s)', 'double'),
      M('valueOf', '(double d)', 'Double'),
      M('doubleValue', '()', 'double'),
    ],
    Boolean: [
      M('parseBoolean', '(String s)', 'boolean'),
      M('valueOf', '(boolean b)', 'Boolean'),
    ],
    ArrayList: [
      M('add', '(E e)', 'boolean'),
      M('add', '(int index, E e)', 'void'),
      M('addAll', '(Collection c)', 'boolean'),
      M('get', '(int index)', 'E'),
      M('set', '(int index, E e)', 'E'),
      M('remove', '(int index)', 'E'),
      M('remove', '(Object o)', 'boolean'),
      M('size', '()', 'int'),
      M('isEmpty', '()', 'boolean'),
      M('contains', '(Object o)', 'boolean'),
      M('indexOf', '(Object o)', 'int'),
      M('clear', '()', 'void'),
      M('toArray', '()', 'Object[]'),
      M('sort', '(Comparator c)', 'void'),
      M('iterator', '()', 'Iterator'),
    ],
    LinkedList: [
      M('add', '(E e)', 'boolean'),
      M('addFirst', '(E e)', 'void'),
      M('addLast', '(E e)', 'void'),
      M('getFirst', '()', 'E'),
      M('getLast', '()', 'E'),
      M('removeFirst', '()', 'E'),
      M('removeLast', '()', 'E'),
      M('peek', '()', 'E'),
      M('peekFirst', '()', 'E'),
      M('peekLast', '()', 'E'),
      M('poll', '()', 'E'),
      M('pollFirst', '()', 'E'),
      M('pollLast', '()', 'E'),
      M('push', '(E e)', 'void'),
      M('pop', '()', 'E'),
      M('size', '()', 'int'),
      M('isEmpty', '()', 'boolean'),
    ],
    HashSet: [
      M('add', '(E e)', 'boolean'),
      M('remove', '(Object o)', 'boolean'),
      M('contains', '(Object o)', 'boolean'),
      M('size', '()', 'int'),
      M('isEmpty', '()', 'boolean'),
      M('clear', '()', 'void'),
      M('iterator', '()', 'Iterator'),
      M('addAll', '(Collection c)', 'boolean'),
      M('retainAll', '(Collection c)', 'boolean'),
      M('removeAll', '(Collection c)', 'boolean'),
    ],
    TreeSet: [
      M('add', '(E e)', 'boolean'),
      M('remove', '(Object o)', 'boolean'),
      M('contains', '(Object o)', 'boolean'),
      M('first', '()', 'E'),
      M('last', '()', 'E'),
      M('ceiling', '(E e)', 'E'),
      M('floor', '(E e)', 'E'),
      M('higher', '(E e)', 'E'),
      M('lower', '(E e)', 'E'),
      M('pollFirst', '()', 'E'),
      M('pollLast', '()', 'E'),
      M('size', '()', 'int'),
      M('isEmpty', '()', 'boolean'),
    ],
    HashMap: [
      M('put', '(K key, V value)', 'V'),
      M('get', '(Object key)', 'V'),
      M('getOrDefault', '(Object key, V def)', 'V'),
      M('containsKey', '(Object key)', 'boolean'),
      M('containsValue', '(Object value)', 'boolean'),
      M('remove', '(Object key)', 'V'),
      M('keySet', '()', 'Set'),
      M('values', '()', 'Collection'),
      M('entrySet', '()', 'Set'),
      M('size', '()', 'int'),
      M('isEmpty', '()', 'boolean'),
      M('clear', '()', 'void'),
    ],
    TreeMap: [
      M('put', '(K key, V value)', 'V'),
      M('get', '(Object key)', 'V'),
      M('getOrDefault', '(Object key, V def)', 'V'),
      M('containsKey', '(Object key)', 'boolean'),
      M('firstKey', '()', 'K'),
      M('lastKey', '()', 'K'),
      M('ceilingKey', '(K key)', 'K'),
      M('floorKey', '(K key)', 'K'),
      M('higherKey', '(K key)', 'K'),
      M('lowerKey', '(K key)', 'K'),
      M('remove', '(Object key)', 'V'),
      M('keySet', '()', 'Set'),
      M('size', '()', 'int'),
    ],
    PriorityQueue: [
      M('add', '(E e)', 'boolean'),
      M('offer', '(E e)', 'boolean'),
      M('peek', '()', 'E'),
      M('poll', '()', 'E'),
      M('remove', '(Object o)', 'boolean'),
      M('clear', '()', 'void'),
      M('size', '()', 'int'),
      M('isEmpty', '()', 'boolean'),
      M('contains', '(Object o)', 'boolean'),
    ],
    ArrayDeque: [
      M('addFirst', '(E e)', 'void'),
      M('addLast', '(E e)', 'void'),
      M('offerFirst', '(E e)', 'boolean'),
      M('offerLast', '(E e)', 'boolean'),
      M('pollFirst', '()', 'E'),
      M('pollLast', '()', 'E'),
      M('peekFirst', '()', 'E'),
      M('peekLast', '()', 'E'),
      M('push', '(E e)', 'void'),
      M('pop', '()', 'E'),
      M('size', '()', 'int'),
      M('isEmpty', '()', 'boolean'),
    ],
    Stack: [
      M('push', '(E item)', 'E'),
      M('pop', '()', 'E'),
      M('peek', '()', 'E'),
      M('empty', '()', 'boolean'),
      M('search', '(Object o)', 'int'),
      M('size', '()', 'int'),
    ],
    Iterator: [
      M('hasNext', '()', 'boolean'),
      M('next', '()', 'E'),
      M('remove', '()', 'void'),
    ],
    List: [
      M('add', '(E e)', 'boolean'),
      M('get', '(int index)', 'E'),
      M('set', '(int index, E e)', 'E'),
      M('remove', '(int index)', 'E'),
      M('size', '()', 'int'),
      M('isEmpty', '()', 'boolean'),
      M('contains', '(Object o)', 'boolean'),
      M('indexOf', '(Object o)', 'int'),
      M('clear', '()', 'void'),
      M('toArray', '()', 'Object[]'),
      M('iterator', '()', 'Iterator'),
      M('subList', '(int from, int to)', 'List'),
    ],
    Set: [
      M('add', '(E e)', 'boolean'),
      M('remove', '(Object o)', 'boolean'),
      M('contains', '(Object o)', 'boolean'),
      M('size', '()', 'int'),
      M('isEmpty', '()', 'boolean'),
      M('iterator', '()', 'Iterator'),
      M('clear', '()', 'void'),
    ],
    Map: [
      M('put', '(K key, V value)', 'V'),
      M('get', '(Object key)', 'V'),
      M('getOrDefault', '(Object key, V def)', 'V'),
      M('containsKey', '(Object key)', 'boolean'),
      M('remove', '(Object key)', 'V'),
      M('keySet', '()', 'Set'),
      M('values', '()', 'Collection'),
      M('entrySet', '()', 'Set'),
      M('size', '()', 'int'),
      M('isEmpty', '()', 'boolean'),
    ],
    Queue: [
      M('add', '(E e)', 'boolean'),
      M('offer', '(E e)', 'boolean'),
      M('peek', '()', 'E'),
      M('poll', '()', 'E'),
      M('remove', '()', 'E'),
      M('size', '()', 'int'),
      M('isEmpty', '()', 'boolean'),
    ],
    Deque: [
      M('addFirst', '(E e)', 'void'),
      M('addLast', '(E e)', 'void'),
      M('offerFirst', '(E e)', 'boolean'),
      M('offerLast', '(E e)', 'boolean'),
      M('pollFirst', '()', 'E'),
      M('pollLast', '()', 'E'),
      M('peekFirst', '()', 'E'),
      M('peekLast', '()', 'E'),
      M('push', '(E e)', 'void'),
      M('pop', '()', 'E'),
    ],
    System: [
      M('currentTimeMillis', '()', 'long'),
      M('nanoTime', '()', 'long'),
      M('exit', '(int status)'),
      M('arraycopy', '(Object src, int sp, Object dst, int dp, int n)', 'void'),
      M('gc', '()'),
      M('lineSeparator', '()', 'String'),
    ],
    Object: [
      M('toString', '()', 'String'),
      M('equals', '(Object o)', 'boolean'),
      M('hashCode', '()', 'int'),
      M('getClass', '()', 'Class'),
    ],
  };

  /** Field-like members: name → type (used for chains such as System.out). */
  const FIELDS = {
    System: { out: 'PrintStream', err: 'PrintStream', in: 'InputStream' },
  };

  const STATIC_NAMES = new Set(Object.keys(MEMBERS));
  const TYPE_KEYWORDS = new Set(['final', 'var', 'static', 'volatile', 'transient']);

  const KIND_FIELD = 'field';
  const KIND_METHOD = 'method';

  /* ------------------------------------------------------------------ *
   * small helpers
   * ------------------------------------------------------------------ */

  function enabled() {
    return localStorage.getItem(LS_SUGGEST) !== '0';
  }

  /** Normalize a declared type to a table key (strip generics; arrays -> ANY_ARRAY). */
  function baseType(t) {
    if (!t) return null;
    t = t.trim();
    if (t.endsWith('[]')) return 'ANY_ARRAY';
    const gi = t.indexOf('<');
    return gi > 0 ? t.slice(0, gi).trim() : t;
  }

  function childByIdentifier(node) {
    for (let i = 0; i < node.childCount; i++) {
      const c = node.child(i);
      if (c.type === 'identifier') return c;
    }
    return null;
  }

  /** Best-effort declared type for "TYPE name …" text (keywords tolerated). */
  function typeFromTokens(text, name) {
    try {
      const beforeEq = String(text).split(/[=;]/)[0];
      const tokens = beforeEq.trim().split(/\s+/);
      const idx = tokens.indexOf(name);
      if (idx <= 0) return null;
      const type = tokens.slice(0, idx)
        .filter((t) => !TYPE_KEYWORDS.has(t) && !/^@/.test(t))
        .join(' ');
      return type || null;
    } catch (e) {
      return null;
    }
  }

  /* ------------------------------------------------------------------ *
   * tree walking
   * ------------------------------------------------------------------ */

  const OUTER_TYPES = new Set([
    'class_declaration', 'interface_declaration', 'record_declaration', 'enum_declaration',
    'method_declaration', 'constructor_declaration', 'lambda_expression',
  ]);

  /** Last child starting at or before idx — a cheap descendantForIndex stand-in. */
  function nodeAt(root, idx) {
    let n = root;
    while (n.childCount > 0) {
      let next = null;
      for (let i = 0; i < n.childCount; i++) {
        const c = n.child(i);
        if (c.startIndex <= idx) next = c;
        else break;
      }
      if (!next || next === n) break;
      n = next;
    }
    return n;
  }

  /** Same-file user types: name → { kind, methods: [], fields: [{name, type}] }. */
  function collectClasses(root) {
    const classes = new Map();
    (function walk(node) {
      if (node.type === 'class_declaration' || node.type === 'interface_declaration' ||
          node.type === 'record_declaration' || node.type === 'enum_declaration') {
        const nameNode = childByIdentifier(node);
        const name = nameNode ? nameNode.text : null;
        if (name) {
          const info = { kind: node.type, methods: [], fields: [] };
          collectDirectMembers(node, info);
          classes.set(name, info);
          return; // nested types get indexed on their own outer walk
        }
      }
      for (let i = 0; i < node.childCount; i++) walk(node.child(i));
    })(root);
    return classes;
  }

  function collectDirectMembers(owner, info) {
    (function walk(node, first) {
      // Handle members before the descent guard: a method/field child must be
      // collected even though both live in OUTER_TYPES-ish territory.
      if (node.type === 'method_declaration' || node.type === 'constructor_declaration') {
        const nameNode = childByIdentifier(node);
        if (nameNode) info.methods.push(nameNode.text);
        return;
      }
      if (node.type === 'field_declaration') {
        for (let i = 0; i < node.childCount; i++) {
          const c = node.child(i);
          if (c.type !== 'variable_declarator') continue;
          const nameNode = childByIdentifier(c);
          const nm = nameNode ? nameNode.text : null;
          if (nm) {
            const prefix = node.text.slice(0, c.startIndex - node.startIndex);
            info.fields.push({ name: nm, type: typeFromTokens(prefix + nm, nm) || '?' });
          }
        }
        return;
      }
      if (!first && OUTER_TYPES.has(node.type)) return; // don't descend into nested types/methods
      for (let i = 0; i < node.childCount; i++) walk(node.child(i), false);
    })(owner, true);
  }

  /**
   * Variables visible at the cursor: method parameters, locals declared before
   * the cursor, enhanced-for / catch bindings, and enclosing-class fields.
   * Returns Map<name, rawDeclaredType> (latest declaration wins).
   */
  function scopeAt(root, text, cursorByte) {
    const leaf = nodeAt(root, Math.max(0, Math.min(cursorByte, text.length)));
    let method = null;
    let cls = null;
    for (let p = leaf; p; p = p.parent) {
      if (!method && (p.type === 'method_declaration' || p.type === 'constructor_declaration' ||
          p.type === 'lambda_expression')) {
        method = p;
      }
      if (!cls && (p.type === 'class_declaration' || p.type === 'interface_declaration' ||
          p.type === 'record_declaration' || p.type === 'enum_declaration')) {
        cls = p;
      }
      if (method && cls) break;
    }

    const out = [];
    const add = (name, type) => { if (name) out.push({ name, type }); };

    if (method) {
      (function walk(node) {
        const t = node.type;
        if (t === 'formal_parameter') {
          const nameNode = childByIdentifier(node);
          if (nameNode && node.startIndex < cursorByte) {
            add(nameNode.text, typeFromTokens(node.text, nameNode.text));
          }
          return;
        }
        if (t === 'catch_formal_parameter') {
          const m = /catch\s*\(\s*([^()]+?)\s+([A-Za-z_][A-Za-z0-9_]*)\s*\)/.exec(node.text);
          if (m && node.startIndex < cursorByte) add(m[2], m[1].trim());
          return;
        }
        if (t === 'enhanced_for_statement') {
          const m = /\(([^()]+?)\s+([A-Za-z_][A-Za-z0-9_]*)\s*:/.exec(node.text);
          if (m && node.startIndex < cursorByte) add(m[2], m[1].trim());
          return;
        }
        if (t === 'local_variable_declaration') {
          for (let i = 0; i < node.childCount; i++) {
            const c = node.child(i);
            if (c.type !== 'variable_declarator' || c.startIndex >= cursorByte) continue;
            const nameNode = childByIdentifier(c);
            if (!nameNode) continue;
            const prefix = node.text.slice(0, c.startIndex - node.startIndex);
            add(nameNode.text, typeFromTokens(prefix + nameNode.text, nameNode.text));
          }
          return;
        }
        for (let i = 0; i < node.childCount; i++) {
          const c = node.child(i);
          if (c.type !== 'method_declaration' && c.type !== 'constructor_declaration' &&
              c.type !== 'lambda_expression' &&
              (c.type === 'class_declaration' || c.type === 'interface_declaration' ||
               c.type === 'record_declaration' || c.type === 'enum_declaration')) {
            continue; // skip nested classes while collecting locals
          }
          walk(c);
        }
      })(method);
    }

    if (cls) {
      (function walk(node, first) {
        if (!first && OUTER_TYPES.has(node.type)) return;
        if (node.type === 'field_declaration') {
          for (let i = 0; i < node.childCount; i++) {
            const c = node.child(i);
            if (c.type !== 'variable_declarator' || c.startIndex >= cursorByte) continue;
            const nameNode = childByIdentifier(c);
            if (!nameNode) continue;
            const prefix = node.text.slice(0, c.startIndex - node.startIndex);
            add(nameNode.text, typeFromTokens(prefix + nameNode.text, nameNode.text));
          }
          return;
        }
        for (let i = 0; i < node.childCount; i++) walk(node.child(i), false);
      })(cls, true);
    }

    const vars = new Map();
    for (const v of out.reverse()) vars.set(v.name, v.type);
    return vars;
  }

  /* ------------------------------------------------------------------ *
   * receiver resolution
   * ------------------------------------------------------------------ */

  function memberReturnType(type, memberName) {
    const fields = FIELDS[type];
    if (fields && fields[memberName]) return fields[memberName];
    const table = MEMBERS[type];
    if (table) {
      for (const e of table) {
        if (e[0] === memberName && e[2]) return e[2];
      }
    }
    return null;
  }

  /** Resolve a chain of segments (["sc"]) or (["System","out"]) to a type key. */
  function resolveChain(parts, scopeVars, classesIndex) {
    let type = null;
    parts.forEach((part, i) => {
      if (type === null) {
        if (scopeVars.has(part)) {
          // Keep whatever the variable declares; unknown/uncurated types simply
          // resolve to nothing downstream (or to userTypeItems when the type is
          // a same-file class), so don't pre-filter against the JDK tables.
          type = baseType(scopeVars.get(part));
        } else if (classesIndex.has(part)) {
          type = part;
        } else if (STATIC_NAMES.has(part)) {
          type = part;
        } else {
          type = null;
        }
      } else {
        const next = memberReturnType(type, part);
        type = next ? baseType(next) : null;
      }
    });
    return type;
  }

  /* ------------------------------------------------------------------ *
   * Monaco suggestions
   * ------------------------------------------------------------------ */

  function monacoKind(kind) {
    const k = window.monaco.languages.CompletionItemKind;
    return kind === KIND_FIELD ? k.Field
      : kind === KIND_METHOD ? k.Function
        : kind === 'variable' ? k.Variable
          : k.Class;
  }

  function toItem(range, label, kind, detail, insertText, isSnippet) {
    const rules = window.monaco.languages.CompletionItemInsertTextRule;
    return {
      label,
      kind: monacoKind(kind),
      detail: detail || '',
      insertText,
      filterText: label,
      insertTextRules: isSnippet ? rules.InsertAsSnippet : rules.None,
      range,
    };
  }

  function methodItems(type, range) {
    const items = [];
    for (const [name, params] of MEMBERS[type] || []) {
      const zeroArg = params === '()';
      items.push(toItem(range, name, KIND_METHOD, type + '.' + name + params,
        name + (zeroArg ? '()' : '($0)'), true));
    }
    return items;
  }

  function arrayItems(range) {
    return [
      toItem(range, 'length', KIND_FIELD, 'int · array length', 'length', false),
      toItem(range, 'clone', KIND_METHOD, 'Object clone()', 'clone()', false),
    ];
  }

  function userTypeItems(info, range) {
    const items = [];
    for (const f of info.fields || []) {
      items.push(toItem(range, f.name, KIND_FIELD, (f.type === '?' ? '' : f.type + ' · ') + 'field', f.name, false));
    }
    for (const m of info.methods || []) {
      items.push(toItem(range, m, KIND_METHOD, m + '()', m + '($0)', true));
    }
    return items;
  }

  /**
   * Parse the member context from the text before the cursor.
   * "sc.n"            → { chain: ["sc"],        partial: "n" }
   * "System.out."     → { chain: ["System","out"], partial: null }
   * Returns null when not completing a member chain.
   */
  function memberContextAt(textBeforeCursor) {
    const m = /([A-Za-z_][A-Za-z0-9_]*)((?:\s*\.\s*[A-Za-z_][A-Za-z0-9_]*)*)\.\s*([A-Za-z_][A-Za-z0-9_]*)?$/
      .exec(textBeforeCursor);
    if (!m) return null;
    const chain = [m[1]];
    (m[2] || '').replace(/\.\s*([A-Za-z_][A-Za-z0-9_]*)/g, (_, s) => { chain.push(s); return ''; });
    return { chain, partial: m[3] || null };
  }

  function buildItems(model, position, tree) {
    const word = model.getWordUntilPosition(position);
    const range = {
      startLineNumber: position.lineNumber,
      endLineNumber: position.lineNumber,
      startColumn: word.startColumn,
      endColumn: word.endColumn,
    };
    const text = tree.text;
    const cursorUtf16 = utf16OffsetOf(model, position);
    const beforeCursor = text.slice(0, cursorUtf16);
    const ctx = memberContextAt(beforeCursor);

    const scopeVars = scopeAt(tree.root, text, utf16ToByte(text, cursorUtf16));
    const classesIndex = classesFor(model, tree);

    if (!ctx) {
      // Plain identifier: in-scope variables and same-file type names, on top of
      // the snippet/keyword provider registered in app.js.
      const items = [];
      for (const [name, type] of scopeVars) {
        items.push(toItem(range, name, 'variable',
          (type && type !== '?' ? type + ' · ' : '') + 'variable', name, false));
      }
      for (const name of classesIndex.keys()) {
        items.push(toItem(range, name, 'class', 'class', name, false));
      }
      return items;
    }

    const type = resolveChain(ctx.chain, scopeVars, classesIndex);
    if (!type) return [];
    if (type === 'ANY_ARRAY') return arrayItems(range);
    if (classesIndex.has(type)) return userTypeItems(classesIndex.get(type), range);
    if (type === 'InputStream') return [];
    return methodItems(type, range);
  }

  /* ---- UTF-16 ↔ byte mapping (Monaco columns are UTF-16, tree-sitter bytes) ---- */

  function utf16OffsetOf(model, position) {
    let off = 0;
    for (let l = 1; l < position.lineNumber; l++) off += model.getLineLength(l) + 1;
    return off + (position.column - 1);
  }

  function utf16ToByte(text, target) {
    let bytes = 0;
    let u = 0;
    let i = 0;
    while (i < text.length && u < target) {
      const cp = text.codePointAt(i);
      bytes += cp > 0x7ff ? 3 : cp > 0x7f ? 2 : 1;
      const wide = cp > 0xffff;
      i += wide ? 2 : 1;
      u += wide ? 2 : 1;
    }
    return bytes;
  }

  /* ------------------------------------------------------------------ *
   * Monaco registration
   * ------------------------------------------------------------------ */

  let registered = false;
  const classCache = { model: null, version: -1, classes: null };

  function classesFor(model, tree) {
    if (classCache.model !== model || classCache.version !== model.getVersionId() || !classCache.classes) {
      classCache.model = model;
      classCache.version = model.getVersionId();
      classCache.classes = collectClasses(tree.root);
    }
    return classCache.classes;
  }

  window.AlgoLabCompletions = {
    /** True when the engine is usable (tree-sitter ready). */
    active: function () {
      return !!(window.AlgoLabTS && window.AlgoLabTS.isReady());
    },
    register: function () {
      if (registered || !window.monaco || !monaco.languages) return;
      registered = true;
      monaco.languages.registerCompletionItemProvider('java', {
        triggerCharacters: ['.'],
        provideCompletionItems(model, position) {
          if (!enabled()) return { suggestions: [] };
          const tree = window.AlgoLabTS && window.AlgoLabTS.tree(model);
          if (!tree) return { suggestions: [] };
          try {
            return { suggestions: buildItems(model, position, tree) };
          } catch (e) {
            console.warn('[java-completions]', e); // never break typing, but stay visible
            return { suggestions: [] };
          }
        },
      });
    },
  };
})();
