/**
 * AlgoLab editor themes — palette data only (no Monaco dependency).
 * `mode` also drives the light/dark chrome skin. Swatches/keywords are
 * faithful family approximations of the well-known schemes.
 */
window.ALGOLAB_THEMES = [
  { id: 'system', label: 'System', mode: 'auto', desc: 'Follow the OS (light or dark)' },

  // ---------- dark ----------
  { id: 'one-dark', label: 'One Dark', mode: 'dark', bg: '#282c34', c: { bg: '#282c34', fg: '#abb2bf', comment: '#5c6370', string: '#98c379', number: '#d19a66', keyword: '#c678dd', func: '#61afef', type: '#e5c07b' } },
  { id: 'catppuccin-mocha', label: 'Catppuccin Mocha', mode: 'dark', bg: '#1e1e2e', c: { bg: '#1e1e2e', fg: '#cdd6f4', comment: '#6c7086', string: '#a6e3a1', number: '#fab387', keyword: '#cba6f7', func: '#89b4fa', type: '#f9e2af' } },
  { id: 'monokai', label: 'Monokai', mode: 'dark', bg: '#272822', c: { bg: '#272822', fg: '#f8f8f2', comment: '#75715e', string: '#e6db74', number: '#ae81ff', keyword: '#f92672', func: '#a6e22c', type: '#66d9ef' } },
  { id: 'tomorrow-night', label: 'Tomorrow Night', mode: 'dark', bg: '#1d1f21', c: { bg: '#1d1f21', fg: '#c5c8c6', comment: '#969896', string: '#b5bd68', number: '#de935f', keyword: '#cc6666', func: '#81a2be', type: '#f0c674' } },
  { id: 'ayu-dark', label: 'Ayu Dark', mode: 'dark', bg: '#0a0e14', c: { bg: '#0a0e14', fg: '#b3b1ad', comment: '#626a73', string: '#aad94c', number: '#ffb454', keyword: '#ff8f40', func: '#39bae6', type: '#ffd580' } },
  { id: 'material-ocean', label: 'Material Ocean', mode: 'dark', bg: '#0f111a', c: { bg: '#0f111a', fg: '#8f93a2', comment: '#464b5d', string: '#c3e88d', number: '#f78c6c', keyword: '#c792ea', func: '#82aaff', type: '#ffcb6b' } },
  { id: 'dracula', label: 'Dracula', mode: 'dark', bg: '#282a36', c: { bg: '#282a36', fg: '#f8f8f2', comment: '#6272a4', string: '#f1fa8c', number: '#bd93f9', keyword: '#ff79c6', func: '#50fa7b', type: '#8be9fd' } },
  { id: 'nord', label: 'Nord', mode: 'dark', bg: '#2e3440', c: { bg: '#2e3440', fg: '#d8dee9', comment: '#616e88', string: '#a3be8c', number: '#b48ead', keyword: '#81a1c1', func: '#88c0d0', type: '#8fbcbb' } },
  { id: 'tokyo-night', label: 'Tokyo Night', mode: 'dark', bg: '#1a1b26', c: { bg: '#1a1b26', fg: '#c0caf5', comment: '#565f89', string: '#9ece6a', number: '#ff9e64', keyword: '#bb9af7', func: '#7aa2f7', type: '#2ac3de' } },
  { id: 'solarized-dark', label: 'Solarized Dark', mode: 'dark', bg: '#002b36', c: { bg: '#002b36', fg: '#839496', comment: '#586e75', string: '#2aa198', number: '#d33682', keyword: '#859900', func: '#268bd2', type: '#b58900' } },
  { id: 'github-dark', label: 'GitHub Dark', mode: 'dark', bg: '#0d1117', c: { bg: '#0d1117', fg: '#c9d1d9', comment: '#8b949e', string: '#a5d6ff', number: '#79c0ff', keyword: '#ff7b72', func: '#d2a8ff', type: '#7ee787' } },
  { id: 'gruvbox-dark', label: 'Gruvbox Dark', mode: 'dark', bg: '#282828', c: { bg: '#282828', fg: '#ebdbb2', comment: '#928374', string: '#b8bb26', number: '#d3869b', keyword: '#fb4934', func: '#83a598', type: '#fabd2f' } },

  // ---------- light ----------
  { id: 'one-light', label: 'One Light', mode: 'light', bg: '#fafafa', c: { bg: '#fafafa', fg: '#383a42', comment: '#a0a1a7', string: '#50a14f', number: '#986801', keyword: '#a626a4', func: '#4078f2', type: '#c18401' } },
  { id: 'catppuccin-latte', label: 'Catppuccin Latte', mode: 'light', bg: '#eff1f5', c: { bg: '#eff1f5', fg: '#4c4f69', comment: '#8c8fa1', string: '#40a02b', number: '#fe640b', keyword: '#8839ef', func: '#1e66f5', type: '#df8e1d' } },
  { id: 'monokai-light', label: 'Monokai Light', mode: 'light', bg: '#f6f6f4', c: { bg: '#f6f6f4', fg: '#47463f', comment: '#908f82', string: '#728e2d', number: '#b86b2d', keyword: '#a8479a', func: '#3f6fb5', type: '#c4820c' } },
  { id: 'tomorrow', label: 'Tomorrow', mode: 'light', bg: '#ffffff', c: { bg: '#ffffff', fg: '#4d4d4c', comment: '#8e908c', string: '#718c00', number: '#f5871f', keyword: '#8959a8', func: '#4271ae', type: '#c99e00' } },
  { id: 'ayu-light', label: 'Ayu Light', mode: 'light', bg: '#fafafa', c: { bg: '#fafafa', fg: '#5c6773', comment: '#abb0b6', string: '#86b300', number: '#fa8d3e', keyword: '#f07171', func: '#36a3d9', type: '#ffaa33' } },
  { id: 'material-light', label: 'Material Light', mode: 'light', bg: '#fafafa', c: { bg: '#fafafa', fg: '#37474f', comment: '#90a4ae', string: '#2e7d32', number: '#f57c00', keyword: '#7c4dff', func: '#0277bd', type: '#c62828' } },
  { id: 'github-light', label: 'GitHub Light', mode: 'light', bg: '#ffffff', c: { bg: '#ffffff', fg: '#24292f', comment: '#6a737d', string: '#032f62', number: '#005cc5', keyword: '#d73a49', func: '#6f42c1', type: '#22863a' } },
  { id: 'solarized-light', label: 'Solarized Light', mode: 'light', bg: '#fdf6e3', c: { bg: '#fdf6e3', fg: '#657b83', comment: '#93a1a1', string: '#2aa198', number: '#d33682', keyword: '#859900', func: '#268bd2', type: '#b58900' } },
  { id: 'rose-pine-dawn', label: 'Rosé Pine Dawn', mode: 'light', bg: '#faf4ed', c: { bg: '#faf4ed', fg: '#575279', comment: '#9893a5', string: '#ea9d34', number: '#d7827e', keyword: '#907aa9', func: '#56949f', type: '#286983' } },
  { id: 'quiet-light', label: 'Quiet Light', mode: 'light', bg: '#f5f5f5', c: { bg: '#f5f5f5', fg: '#333333', comment: '#9a9a9a', string: '#448c27', number: '#aa6708', keyword: '#7c3cff', func: '#1a1a1a', type: '#0b6121' } },
];

/** Fallbacks used when the theme is "System" but the OS doesn't match anything. */
window.ALGOLAB_DEFAULT_DARK = 'one-dark';
window.ALGOLAB_DEFAULT_LIGHT = 'one-light';

/** Convenience: look a theme up by id. */
window.ALGOLAB_THEME = (id) => window.ALGOLAB_THEMES.find((t) => t.id === id) || null;

/* =====================================================================
   Color math — tiny helpers shared by the chrome palette + semantic rules.
   ===================================================================== */

function hexToRgb(hex) {
  const h = hex.replace('#', '');
  const n = h.length === 3
    ? h.split('').map((c) => c + c).join('')
    : h;
  const v = parseInt(n, 16);
  return { r: (v >> 16) & 255, g: (v >> 8) & 255, b: v & 255 };
}

/** t=0 → a, t=1 → b (linear mix). */
function mixHex(a, b, t) {
  const A = hexToRgb(a);
  const B = hexToRgb(b);
  const r = Math.round(A.r + (B.r - A.r) * t);
  const g = Math.round(A.g + (B.g - A.g) * t);
  const bl = Math.round(A.b + (B.b - A.b) * t);
  return '#' + [r, g, bl].map((x) => Math.max(0, Math.min(255, x)).toString(16).padStart(2, '0')).join('');
}

/** Shade toward white (p>0) or black (p<0); p is a 0..1 fraction. */
function shadeHex(hex, p) {
  return p >= 0 ? mixHex(hex, '#ffffff', p) : mixHex(hex, '#000000', -p);
}

function rgbaHex(hex, alpha) {
  const { r, g, b } = hexToRgb(hex);
  return `rgba(${r}, ${g}, ${b}, ${alpha})`;
}

function luminance(hex) {
  const { r, g, b } = hexToRgb(hex);
  return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255;
}

/** Extra semantic-role colors derived from a theme palette (see rules in app.js). */
function algolabExtra(c) {
  return {
    prop: mixHex(c.func, c.fg, 0.55),      // fields / member access
    constC: mixHex(c.number, c.func, 0.5), // UPPER_CASE constants
    ns: mixHex(c.comment, c.fg, 0.55),     // package / import namespaces
  };
}

/** Build the full CSS-variable map that makes the app chrome match a theme. */
function algolabChrome(t) {
  const dark = t.mode === 'dark';
  const bg = t.bg, fg = t.c.fg, cm = t.c.comment, func = t.c.func;
  const v = {};
  v.page = bg;
  v['page-soft'] = rgbaHex(bg, dark ? 0.8 : 0.88);
  if (dark) {
    v.chrome = shadeHex(bg, 0.028);
    v['panel-2'] = shadeHex(bg, 0.07);
    v['panel-3'] = shadeHex(bg, 0.125);
    v['panel-4'] = shadeHex(bg, 0.19);
    v.edge = shadeHex(bg, 0.085);
    v['edge-2'] = shadeHex(bg, 0.15);
  } else {
    v.chrome = shadeHex(bg, -0.025);
    v['panel-2'] = shadeHex(bg, -0.05);
    v['panel-3'] = shadeHex(bg, -0.1);
    v['panel-4'] = shadeHex(bg, -0.16);
    v.edge = shadeHex(bg, -0.11);
    v['edge-2'] = shadeHex(bg, -0.05);
  }
  v['chrome-soft'] = rgbaHex(v.chrome, dark ? 0.72 : 0.85);
  v['panel-2-soft'] = rgbaHex(v['panel-2'], 0.65);
  v['edge-soft'] = rgbaHex(v.edge, dark ? 0.8 : 0.85);
  v['t-1'] = fg;
  v['t-2'] = mixHex(fg, bg, dark ? 0.14 : 0.1);
  v['t-3'] = mixHex(fg, bg, dark ? 0.3 : 0.22);
  v['t-4'] = cm;
  v['t-5'] = mixHex(cm, bg, dark ? 0.4 : 0.35);
  v['t-6'] = mixHex(cm, bg, dark ? 0.62 : 0.55);

  v.accent = func;
  v['accent-strong'] = dark ? shadeHex(func, 0.16) : shadeHex(func, -0.2);
  v['accent-ink'] = luminance(func) > 0.6 ? '#0c0c0f' : '#ffffff';
  v['accent-glow'] = rgbaHex(func, 0.45);
  v['accent-a'] = rgbaHex(func, 0.32);
  return v;
}

/** Paint the current concrete theme onto the whole app chrome (CSS vars on <html>). */
function algolabApplyChrome(id) {
  try {
    const t = window.ALGOLAB_THEME ? window.ALGOLAB_THEME(id) : null;
    if (!t || t.mode === 'auto') return;
    const vars = algolabChrome(t);
    const st = document.documentElement.style;
    for (const k in vars) st.setProperty('--' + k, vars[k]);
  } catch (e) { /* non-fatal */ }
}

window.ALGOLAB_MIX = mixHex;
window.ALGOLAB_EXTRA = algolabExtra;
window.ALGOLAB_APPLY_CHROME = algolabApplyChrome;
