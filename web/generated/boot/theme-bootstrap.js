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
    'html[data-ag-color-theme="cyberpunk"]{--background:#0c0716;--foreground:#f3e8ff;--primary:#ec4899;--primary-foreground:#ffffff;--secondary:#1f1235;--secondary-foreground:#e9d5ff;--muted:#140b24;--muted-foreground:#a78bfa;--placeholder:#6d28d9;--border:#2e1065;--card:#170d2b;--card-border:#3b0764;--sidebar:#110820;--sidebar-secondary:#1c0f33;--sidebar-muted:#140b24;--link:#22d3ee;--success:#34d399;--warning:#fbbf24;--error:#fb7185;--code-foreground:#f472b6;--bg-main:#0c0716;--bg-card:rgba(23,13,43,0.85);--accent-glow:#ec4899;}',
    'html[data-ag-color-theme="ocean"]{--background:#061325;--foreground:#e0f2fe;--primary:#0ea5e9;--primary-foreground:#ffffff;--secondary:#0c2442;--secondary-foreground:#bae6fd;--muted:#091a32;--muted-foreground:#7dd3fc;--placeholder:#0369a1;--border:#163a63;--card:#0b1f3a;--card-border:#1e4976;--sidebar:#07172d;--sidebar-secondary:#0f2847;--sidebar-muted:#091a32;--link:#38bdf8;--success:#2dd4bf;--warning:#fbbf24;--error:#f87171;--code-foreground:#7dd3fc;--bg-main:#061325;--bg-card:rgba(11,31,58,0.85);--accent-glow:#0ea5e9;}',
    'html[data-ag-color-theme="forest"]{--background:#06150e;--foreground:#dcfce7;--primary:#10b981;--primary-foreground:#022c22;--secondary:#0f2e20;--secondary-foreground:#a7f3d0;--muted:#0a2016;--muted-foreground:#6ee7b7;--placeholder:#047857;--border:#14432e;--card:#0c261a;--card-border:#195238;--sidebar:#081b12;--sidebar-secondary:#113323;--sidebar-muted:#0a2016;--link:#34d399;--success:#a3e635;--warning:#facc15;--error:#f87171;--code-foreground:#6ee7b7;--bg-main:#06150e;--bg-card:rgba(12,38,26,0.85);--accent-glow:#10b981;}',
    'html[data-ag-color-theme="sunset"]{--background:#170b08;--foreground:#ffedd5;--primary:#f97316;--primary-foreground:#ffffff;--secondary:#2d1610;--secondary-foreground:#fed7aa;--muted:#21100b;--muted-foreground:#fdba74;--placeholder:#9a3412;--border:#431e14;--card:#26120c;--card-border:#572619;--sidebar:#1b0d09;--sidebar-secondary:#311811;--sidebar-muted:#21100b;--link:#fbbf24;--success:#4ade80;--warning:#facc15;--error:#fb7185;--code-foreground:#fdba74;--bg-main:#170b08;--bg-card:rgba(38,18,12,0.85);--accent-glow:#f97316;}',
    'html[data-ag-color-theme="aurora"]{--background:#0b091c;--foreground:#ede9fe;--primary:#8b5cf6;--primary-foreground:#ffffff;--secondary:#1b163d;--secondary-foreground:#ddd6fe;--muted:#120e2b;--muted-foreground:#a78bfa;--placeholder:#5b21b6;--border:#2c235c;--card:#151133;--card-border:#372c70;--sidebar:#0e0b22;--sidebar-secondary:#1f1945;--sidebar-muted:#120e2b;--link:#38bdf8;--success:#34d399;--warning:#fbbf24;--error:#fb7185;--code-foreground:#c4b5fd;--bg-main:#0b091c;--bg-card:rgba(21,17,51,0.85);--accent-glow:#8b5cf6;}',
    'html[data-ag-color-theme="sakura"]{--background:#1a0b14;--foreground:#fce7f3;--primary:#f472b6;--primary-foreground:#2a0819;--secondary:#331526;--secondary-foreground:#fbcfe8;--muted:#240e1b;--muted-foreground:#f9a8d4;--placeholder:#9d174d;--border:#4d1d38;--card:#2b1120;--card-border:#612447;--sidebar:#1e0c17;--sidebar-secondary:#38172a;--sidebar-muted:#240e1b;--link:#fb7185;--success:#4ade80;--warning:#fbbf24;--error:#fda4af;--code-foreground:#f9a8d4;--bg-main:#1a0b14;--bg-card:rgba(43,17,32,0.85);--accent-glow:#f472b6;}'
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

