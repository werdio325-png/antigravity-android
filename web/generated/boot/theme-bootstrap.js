// Fast theme bootstrap. Runs before the app bundle so the first paint already
// uses the host-selected color scheme. Fixed: honor "light" instead of always
// forcing the dark class.
(function () {
  'use strict';

  var theme = 'system';
  try {
    var params = new URLSearchParams(window.location.search);
    theme = params.get('hostTheme') || window.__AG_HOST_THEME__ || 'system';
  } catch (e) {
    theme = window.__AG_HOST_THEME__ || 'system';
  }
  if (theme === 'system') {
    var isDark;
    if (typeof window !== 'undefined' && window.Android && typeof window.Android.getTheme === 'function') {
      try {
        var t = window.Android.getTheme();
        if (t === 'dark' || t === 'light') isDark = (t === 'dark');
      } catch (e) {}
    }
    if (isDark === undefined && typeof window !== 'undefined' && window.__AG_RESOLVED_THEME__) {
      isDark = (window.__AG_RESOLVED_THEME__ === 'dark');
    }
    if (isDark === undefined) {
      isDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches;
    }
    theme = isDark ? 'dark' : 'light';
  } else {
    theme = theme === 'light' ? 'light' : 'dark';
  }

  document.documentElement.style.colorScheme = theme;
  document.documentElement.classList.add(theme);

  var COLOR_THEMES = [
    { id: 'default', name: 'Default', accent: '#007acc', bg: '#101010' },
    { id: 'cyberpunk', name: 'Cyberpunk', accent: '#ec4899', bg: '#0c0716' },
    { id: 'ocean', name: 'Ocean', accent: '#0ea5e9', bg: '#061325' },
    { id: 'forest', name: 'Forest', accent: '#10b981', bg: '#06150e' },
    { id: 'sunset', name: 'Sunset', accent: '#f97316', bg: '#170b08' },
    { id: 'aurora', name: 'Aurora', accent: '#8b5cf6', bg: '#0b091c' },
    { id: 'sakura', name: 'Sakura', accent: '#f472b6', bg: '#1a0b14' }
  ];

  var css = [
    'html[data-ag-color-theme="cyberpunk"]{--background:#0c0716 !important;--foreground:#f3e8ff !important;--primary:#ec4899 !important;--primary-foreground:#ffffff !important;--secondary:#1f1235 !important;--secondary-foreground:#e9d5ff !important;--muted:#140b24 !important;--muted-foreground:#a78bfa !important;--placeholder:#6d28d9 !important;--border:#2e1065 !important;--card:#170d2b !important;--card-border:#3b0764 !important;--sidebar:#110820 !important;--sidebar-secondary:#1c0f33 !important;--sidebar-muted:#140b24 !important;--link:#22d3ee !important;--success:#34d399 !important;--warning:#fbbf24 !important;--error:#fb7185 !important;--code-foreground:#f472b6 !important;--bg-main:#0c0716 !important;--bg-card:rgba(23,13,43,0.85) !important;--accent-glow:#ec4899 !important;}',
    'html[data-ag-color-theme="ocean"]{--background:#061325 !important;--foreground:#e0f2fe !important;--primary:#0ea5e9 !important;--primary-foreground:#ffffff !important;--secondary:#0c2442 !important;--secondary-foreground:#bae6fd !important;--muted:#091a32 !important;--muted-foreground:#7dd3fc !important;--placeholder:#0369a1 !important;--border:#163a63 !important;--card:#0b1f3a !important;--card-border:#1e4976 !important;--sidebar:#07172d !important;--sidebar-secondary:#0f2847 !important;--sidebar-muted:#091a32 !important;--link:#38bdf8 !important;--success:#2dd4bf !important;--warning:#fbbf24 !important;--error:#f87171 !important;--code-foreground:#7dd3fc !important;--bg-main:#061325 !important;--bg-card:rgba(11,31,58,0.85) !important;--accent-glow:#0ea5e9 !important;}',
    'html[data-ag-color-theme="forest"]{--background:#06150e !important;--foreground:#dcfce7 !important;--primary:#10b981 !important;--primary-foreground:#022c22 !important;--secondary:#0f2e20 !important;--secondary-foreground:#a7f3d0 !important;--muted:#0a2016 !important;--muted-foreground:#6ee7b7 !important;--placeholder:#047857 !important;--border:#14432e !important;--card:#0c261a !important;--card-border:#195238 !important;--sidebar:#081b12 !important;--sidebar-secondary:#113323 !important;--sidebar-muted:#0a2016 !important;--link:#34d399 !important;--success:#a3e635 !important;--warning:#facc15 !important;--error:#f87171 !important;--code-foreground:#6ee7b7 !important;--bg-main:#06150e !important;--bg-card:rgba(12,38,26,0.85) !important;--accent-glow:#10b981 !important;}',
    'html[data-ag-color-theme="sunset"]{--background:#170b08 !important;--foreground:#ffedd5 !important;--primary:#f97316 !important;--primary-foreground:#ffffff !important;--secondary:#2d1610 !important;--secondary-foreground:#fed7aa !important;--muted:#21100b !important;--muted-foreground:#fdba74 !important;--placeholder:#9a3412 !important;--border:#431e14 !important;--card:#26120c !important;--card-border:#572619 !important;--sidebar:#1b0d09 !important;--sidebar-secondary:#311811 !important;--sidebar-muted:#21100b !important;--link:#fbbf24 !important;--success:#4ade80 !important;--warning:#facc15 !important;--error:#fb7185 !important;--code-foreground:#fdba74 !important;--bg-main:#170b08 !important;--bg-card:rgba(38,18,12,0.85) !important;--accent-glow:#f97316 !important;}',
    'html[data-ag-color-theme="aurora"]{--background:#0b091c !important;--foreground:#ede9fe !important;--primary:#8b5cf6 !important;--primary-foreground:#ffffff !important;--secondary:#1b163d !important;--secondary-foreground:#ddd6fe !important;--muted:#120e2b !important;--muted-foreground:#a78bfa !important;--placeholder:#5b21b6 !important;--border:#2c235c !important;--card:#151133 !important;--card-border:#372c70 !important;--sidebar:#0e0b22 !important;--sidebar-secondary:#1f1945 !important;--sidebar-muted:#120e2b !important;--link:#38bdf8 !important;--success:#34d399 !important;--warning:#fbbf24 !important;--error:#fb7185 !important;--code-foreground:#c4b5fd !important;--bg-main:#0b091c !important;--bg-card:rgba(21,17,51,0.85) !important;--accent-glow:#8b5cf6 !important;}',
    'html[data-ag-color-theme="sakura"]{--background:#1a0b14 !important;--foreground:#fce7f3 !important;--primary:#f472b6 !important;--primary-foreground:#2a0819 !important;--secondary:#331526 !important;--secondary-foreground:#fbcfe8 !important;--muted:#240e1b !important;--muted-foreground:#f9a8d4 !important;--placeholder:#9d174d !important;--border:#4d1d38 !important;--card:#2b1120 !important;--card-border:#612447 !important;--sidebar:#1e0c17 !important;--sidebar-secondary:#38172a !important;--sidebar-muted:#240e1b !important;--link:#fb7185 !important;--success:#4ade80 !important;--warning:#fbbf24 !important;--error:#fda4af !important;--code-foreground:#f9a8d4 !important;--bg-main:#1a0b14 !important;--bg-card:rgba(43,17,32,0.85) !important;--accent-glow:#f472b6 !important;}'
  ].join('\n');

  try {
    var st = document.createElement('style');
    st.id = 'ag-color-themes-style';
    st.textContent = css;
    (document.head || document.documentElement).appendChild(st);
  } catch (e) {}

  function applyColorTheme(id) {
    var valid = COLOR_THEMES.some(function (t) { return t.id === id; }) ? id : 'default';
    try { localStorage.setItem('ag_color_theme', valid); } catch (e) {}
    if (valid === 'default') {
      document.documentElement.removeAttribute('data-ag-color-theme');
    } else {
      document.documentElement.setAttribute('data-ag-color-theme', valid);
    }
    return valid;
  }

  var savedColorTheme = 'default';
  try { savedColorTheme = localStorage.getItem('ag_color_theme') || 'default'; } catch (e) {}
  applyColorTheme(savedColorTheme);

  window.__AG_COLOR_THEMES__ = COLOR_THEMES;
  window.__AG_SET_COLOR_THEME__ = applyColorTheme;
})();

