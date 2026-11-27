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
    { id: 'sakura', name: 'Sakura', accent: '#f472b6', bg: '#1a0b14' },
    { id: 'cyberpunk-light', name: 'Cyberpunk White', accent: '#db2777', bg: '#f4f1ea' },
    { id: 'ocean-light', name: 'Ocean White', accent: '#0284c7', bg: '#f0f2f0' },
    { id: 'forest-light', name: 'Forest White', accent: '#059669', bg: '#f1f2ed' },
    { id: 'sunset-light', name: 'Sunset White', accent: '#ea580c', bg: '#f5f0e8' },
    { id: 'aurora-light', name: 'Aurora White', accent: '#7c3aed', bg: '#f2f0f5' },
    { id: 'sakura-light', name: 'Sakura White', accent: '#db2777', bg: '#f5eff2' },
    { id: 'matrix', name: 'The Matrix', accent: '#00ff41', bg: '#020804' }
  ];

  var matrixRainSvg = "url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='10' height='120' viewBox='0 0 10 120'%3E%3Ctext x='1' y='12' fill='%2300ff41' fill-opacity='0.22' font-family='monospace' font-size='9'%3E0%3C/text%3E%3Ctext x='2' y='26' fill='%2300ff41' fill-opacity='0.38' font-family='monospace' font-size='9'%3E1%3C/text%3E%3Ctext x='1' y='40' fill='%2300ff41' fill-opacity='0.55' font-family='monospace' font-size='9'%3E%EF%BD%B6%3C/text%3E%3Ctext x='2' y='54' fill='%2300ff41' fill-opacity='0.72' font-family='monospace' font-size='9'%3E7%3C/text%3E%3Ctext x='1' y='68' fill='%23a7f3d0' fill-opacity='0.95' font-family='monospace' font-size='9'%3E%EF%BE%81%3C/text%3E%3Ctext x='2' y='84' fill='%2300ff41' fill-opacity='0.25' font-family='monospace' font-size='9'%3EA%3C/text%3E%3Ctext x='1' y='98' fill='%2300ff41' fill-opacity='0.5' font-family='monospace' font-size='9'%3E%EF%BE%98%3C/text%3E%3Ctext x='2' y='112' fill='%2386efac' fill-opacity='0.85' font-family='monospace' font-size='9'%3E9%3C/text%3E%3C/svg%3E\")";

  var css = [
    'html[data-ag-color-theme="cyberpunk"]{--background:#0c0716 !important;--foreground:#f3e8ff !important;--primary:#ec4899 !important;--primary-foreground:#ffffff !important;--secondary:#1f1235 !important;--secondary-foreground:#e9d5ff !important;--muted:#140b24 !important;--muted-foreground:#a78bfa !important;--placeholder:#6d28d9 !important;--border:#2e1065 !important;--card:#170d2b !important;--card-border:#3b0764 !important;--sidebar:#110820 !important;--sidebar-secondary:#1c0f33 !important;--sidebar-muted:#140b24 !important;--link:#22d3ee !important;--success:#34d399 !important;--warning:#fbbf24 !important;--error:#fb7185 !important;--code-foreground:#f472b6 !important;--bg-main:#0c0716 !important;--bg-card:rgba(23,13,43,0.85) !important;--accent-glow:#ec4899 !important;}',
    'html[data-ag-color-theme="ocean"]{--background:#061325 !important;--foreground:#e0f2fe !important;--primary:#0ea5e9 !important;--primary-foreground:#ffffff !important;--secondary:#0c2442 !important;--secondary-foreground:#bae6fd !important;--muted:#091a32 !important;--muted-foreground:#7dd3fc !important;--placeholder:#0369a1 !important;--border:#163a63 !important;--card:#0b1f3a !important;--card-border:#1e4976 !important;--sidebar:#07172d !important;--sidebar-secondary:#0f2847 !important;--sidebar-muted:#091a32 !important;--link:#38bdf8 !important;--success:#2dd4bf !important;--warning:#fbbf24 !important;--error:#f87171 !important;--code-foreground:#7dd3fc !important;--bg-main:#061325 !important;--bg-card:rgba(11,31,58,0.85) !important;--accent-glow:#0ea5e9 !important;}',
    'html[data-ag-color-theme="forest"]{--background:#06150e !important;--foreground:#dcfce7 !important;--primary:#10b981 !important;--primary-foreground:#022c22 !important;--secondary:#0f2e20 !important;--secondary-foreground:#a7f3d0 !important;--muted:#0a2016 !important;--muted-foreground:#6ee7b7 !important;--placeholder:#047857 !important;--border:#14432e !important;--card:#0c261a !important;--card-border:#195238 !important;--sidebar:#081b12 !important;--sidebar-secondary:#113323 !important;--sidebar-muted:#0a2016 !important;--link:#34d399 !important;--success:#a3e635 !important;--warning:#facc15 !important;--error:#f87171 !important;--code-foreground:#6ee7b7 !important;--bg-main:#06150e !important;--bg-card:rgba(12,38,26,0.85) !important;--accent-glow:#10b981 !important;}',
    'html[data-ag-color-theme="sunset"]{--background:#170b08 !important;--foreground:#ffedd5 !important;--primary:#f97316 !important;--primary-foreground:#ffffff !important;--secondary:#2d1610 !important;--secondary-foreground:#fed7aa !important;--muted:#21100b !important;--muted-foreground:#fdba74 !important;--placeholder:#9a3412 !important;--border:#431e14 !important;--card:#26120c !important;--card-border:#572619 !important;--sidebar:#1b0d09 !important;--sidebar-secondary:#311811 !important;--sidebar-muted:#21100b !important;--link:#fbbf24 !important;--success:#4ade80 !important;--warning:#facc15 !important;--error:#fb7185 !important;--code-foreground:#fdba74 !important;--bg-main:#170b08 !important;--bg-card:rgba(38,18,12,0.85) !important;--accent-glow:#f97316 !important;}',
    'html[data-ag-color-theme="aurora"]{--background:#0b091c !important;--foreground:#ede9fe !important;--primary:#8b5cf6 !important;--primary-foreground:#ffffff !important;--secondary:#1b163d !important;--secondary-foreground:#ddd6fe !important;--muted:#120e2b !important;--muted-foreground:#a78bfa !important;--placeholder:#5b21b6 !important;--border:#2c235c !important;--card:#151133 !important;--card-border:#372c70 !important;--sidebar:#0e0b22 !important;--sidebar-secondary:#1f1945 !important;--sidebar-muted:#120e2b !important;--link:#38bdf8 !important;--success:#34d399 !important;--warning:#fbbf24 !important;--error:#fb7185 !important;--code-foreground:#c4b5fd !important;--bg-main:#0b091c !important;--bg-card:rgba(21,17,51,0.85) !important;--accent-glow:#8b5cf6 !important;}',
    'html[data-ag-color-theme="sakura"]{--background:#1a0b14 !important;--foreground:#fce7f3 !important;--primary:#f472b6 !important;--primary-foreground:#2a0819 !important;--secondary:#331526 !important;--secondary-foreground:#fbcfe8 !important;--muted:#240e1b !important;--muted-foreground:#f9a8d4 !important;--placeholder:#9d174d !important;--border:#4d1d38 !important;--card:#2b1120 !important;--card-border:#612447 !important;--sidebar:#1e0c17 !important;--sidebar-secondary:#38172a !important;--sidebar-muted:#240e1b !important;--link:#fb7185 !important;--success:#4ade80 !important;--warning:#fbbf24 !important;--error:#fda4af !important;--code-foreground:#f9a8d4 !important;--bg-main:#1a0b14 !important;--bg-card:rgba(43,17,32,0.85) !important;--accent-glow:#f472b6 !important;}',
    'html[data-ag-color-theme="cyberpunk-light"]{--background:#f4f1ea !important;--foreground:#1f1629 !important;--primary:#db2777 !important;--primary-foreground:#ffffff !important;--secondary:#e7e1d8 !important;--secondary-foreground:#4a235a !important;--muted:#ece8df !important;--muted-foreground:#6b4c7a !important;--placeholder:#9c88a8 !important;--border:#d7cfc4 !important;--card:#eae6dd !important;--card-border:#cec5b8 !important;--sidebar:#ede9e0 !important;--sidebar-secondary:#e3ded3 !important;--sidebar-muted:#e7e2d8 !important;--link:#0891b2 !important;--success:#059669 !important;--warning:#d97706 !important;--error:#e11d48 !important;--code-foreground:#be185d !important;--bg-main:#f4f1ea !important;--bg-card:rgba(234,230,221,0.92) !important;--accent-glow:#db2777 !important;}',
    'html[data-ag-color-theme="ocean-light"]{--background:#f0f2f0 !important;--foreground:#0f1e2e !important;--primary:#0284c7 !important;--primary-foreground:#ffffff !important;--secondary:#e2e7e5 !important;--secondary-foreground:#1e3a5f !important;--muted:#e7ebe9 !important;--muted-foreground:#47647d !important;--placeholder:#7d95ab !important;--border:#cdd5d2 !important;--card:#e6eae8 !important;--card-border:#c2ccc8 !important;--sidebar:#e9edea !important;--sidebar-secondary:#dfe5e2 !important;--sidebar-muted:#e3e8e5 !important;--link:#0369a1 !important;--success:#0d9488 !important;--warning:#d97706 !important;--error:#dc2626 !important;--code-foreground:#0369a1 !important;--bg-main:#f0f2f0 !important;--bg-card:rgba(230,234,232,0.92) !important;--accent-glow:#0284c7 !important;}',
    'html[data-ag-color-theme="forest-light"]{--background:#f1f2ed !important;--foreground:#13261b !important;--primary:#059669 !important;--primary-foreground:#ffffff !important;--secondary:#e3e7de !important;--secondary-foreground:#1e4731 !important;--muted:#e8ebe3 !important;--muted-foreground:#476b56 !important;--placeholder:#7d9988 !important;--border:#cfd6c8 !important;--card:#e6eae1 !important;--card-border:#c5cebd !important;--sidebar:#eaede5 !important;--sidebar-secondary:#e0e5da !important;--sidebar-muted:#e4e8de !important;--link:#047857 !important;--success:#16a34a !important;--warning:#ca8a04 !important;--error:#dc2626 !important;--code-foreground:#047857 !important;--bg-main:#f1f2ed !important;--bg-card:rgba(230,234,225,0.92) !important;--accent-glow:#059669 !important;}',
    'html[data-ag-color-theme="sunset-light"]{--background:#f5f0e8 !important;--foreground:#291710 !important;--primary:#ea580c !important;--primary-foreground:#ffffff !important;--secondary:#eae1d5 !important;--secondary-foreground:#5c2b16 !important;--muted:#eee6dc !important;--muted-foreground:#7c4d38 !important;--placeholder:#a88270 !important;--border:#dcd0c0 !important;--card:#ede5da !important;--card-border:#d1c3b0 !important;--sidebar:#f0e9df !important;--sidebar-secondary:#e6ddd0 !important;--sidebar-muted:#eae2d6 !important;--link:#d97706 !important;--success:#16a34a !important;--warning:#ca8a04 !important;--error:#e11d48 !important;--code-foreground:#c2410c !important;--bg-main:#f5f0e8 !important;--bg-card:rgba(237,229,218,0.92) !important;--accent-glow:#ea580c !important;}',
    'html[data-ag-color-theme="aurora-light"]{--background:#f2f0f5 !important;--foreground:#1e1633 !important;--primary:#7c3aed !important;--primary-foreground:#ffffff !important;--secondary:#e5e1ec !important;--secondary-foreground:#3b276b !important;--muted:#eae6f0 !important;--muted-foreground:#5b4b7d !important;--placeholder:#8d80a8 !important;--border:#d4cde0 !important;--card:#e8e4ee !important;--card-border:#c9c1d6 !important;--sidebar:#ece8f2 !important;--sidebar-secondary:#e1dce9 !important;--sidebar-muted:#e6e1ed !important;--link:#0284c7 !important;--success:#059669 !important;--warning:#d97706 !important;--error:#e11d48 !important;--code-foreground:#6d28d9 !important;--bg-main:#f2f0f5 !important;--bg-card:rgba(232,228,238,0.92) !important;--accent-glow:#7c3aed !important;}',
    'html[data-ag-color-theme="sakura-light"]{--background:#f5eff2 !important;--foreground:#2d1523 !important;--primary:#db2777 !important;--primary-foreground:#ffffff !important;--secondary:#eadfe4 !important;--secondary-foreground:#632645 !important;--muted:#efe5ea !important;--muted-foreground:#7d4963 !important;--placeholder:#a87b92 !important;--border:#dcccd4 !important;--card:#ede3e8 !important;--card-border:#d1bec8 !important;--sidebar:#f1e8ec !important;--sidebar-secondary:#e7dbe1 !important;--sidebar-muted:#ebe0e5 !important;--link:#e11d48 !important;--success:#16a34a !important;--warning:#d97706 !important;--error:#be123c !important;--code-foreground:#be185d !important;--bg-main:#f5eff2 !important;--bg-card:rgba(237,227,232,0.92) !important;--accent-glow:#db2777 !important;}',
    'html[data-ag-color-theme="matrix"]{--background:#020804 !important;--foreground:#86efac !important;--primary:#00ff41 !important;--primary-foreground:#021406 !important;--secondary:#071f0e !important;--secondary-foreground:#4ade80 !important;--muted:#041208 !important;--muted-foreground:#22c55e !important;--placeholder:#15803d !important;--border:#0f4d22 !important;--card:#041409 !important;--card-border:#166534 !important;--sidebar:#030e06 !important;--sidebar-secondary:#082410 !important;--sidebar-muted:#041208 !important;--link:#00ff41 !important;--success:#00ff41 !important;--warning:#a3e635 !important;--error:#f87171 !important;--code-foreground:#00ff41 !important;--bg-main:#020804 !important;--bg-card:rgba(4,20,9,0.9) !important;--accent-glow:#00ff41 !important;}',
    '@keyframes agMatrixEdgeRain{0%{background-position:left 0px,right 60px;}100%{background-position:left 120px,right 180px;}}',
    'html[data-ag-color-theme="matrix"] [class*="border-border"],html[data-ag-color-theme="matrix"] .bg-card,html[data-ag-color-theme="matrix"] [role="dialog"],html[data-ag-color-theme="matrix"] aside,html[data-ag-color-theme="matrix"] .rounded-xl,html[data-ag-color-theme="matrix"] .rounded-lg{background-image:' + matrixRainSvg + ',' + matrixRainSvg + ' !important;background-position:left 0px,right 60px;background-size:10px 120px,10px 120px !important;background-repeat:repeat-y,repeat-y !important;animation:agMatrixEdgeRain 2.8s linear infinite !important;box-shadow:0 0 8px rgba(0,255,65,0.16),inset 0 0 6px rgba(0,255,65,0.10) !important;}'
  ].join('\n');

  try {
    var st = document.createElement('style');
    st.id = 'ag-color-themes-style';
    st.textContent = css;
    (document.head || document.documentElement).appendChild(st);
  } catch (e) {}

  var matrixRafId = null;
  function stopMatrixEdgeCanvas() {
    if (matrixRafId !== null) {
      cancelAnimationFrame(matrixRafId);
      matrixRafId = null;
    }
    try {
      var old = document.getElementById('ag-matrix-edge-canvas');
      if (old && old.parentNode) old.parentNode.removeChild(old);
    } catch (e) {}
  }

  function startMatrixEdgeCanvas() {
    stopMatrixEdgeCanvas();
    if (typeof document === 'undefined') return;
    var mount = function () {
      if (document.documentElement.getAttribute('data-ag-color-theme') !== 'matrix') return;
      if (document.getElementById('ag-matrix-edge-canvas')) return;
      var canvas = document.createElement('canvas');
      canvas.id = 'ag-matrix-edge-canvas';
      canvas.style.cssText = 'position:fixed;inset:0;width:100vw;height:100vh;pointer-events:none;z-index:9999;';
      (document.body || document.documentElement).appendChild(canvas);
      var ctx = canvas.getContext('2d');
      if (!ctx) return;
      var chars = '01ﾊﾐﾋｰｳｼﾅﾓﾆｻﾜﾂｵﾘｱﾎﾃﾏｹﾒｴｶｷﾑﾕﾗｾﾈｽﾀﾇﾍ';
      var fontSize = 11;
      var edgeW = 16;
      var drops = [];
      function resize() {
        canvas.width = window.innerWidth;
        canvas.height = window.innerHeight;
        drops = [1, 8, Math.floor(Math.random() * 20), Math.floor(Math.random() * 20)];
      }
      resize();
      window.addEventListener('resize', resize);
      var last = 0;
      function draw(ts) {
        if (document.documentElement.getAttribute('data-ag-color-theme') !== 'matrix') {
          window.removeEventListener('resize', resize);
          stopMatrixEdgeCanvas();
          return;
        }
        if (ts - last > 55) {
          last = ts;
          ctx.clearRect(0, 0, canvas.width, canvas.height);
          ctx.font = fontSize + 'px monospace';
          var xs = [2, 9, Math.max(0, canvas.width - edgeW + 2), Math.max(0, canvas.width - 9)];
          for (var i = 0; i < xs.length; i++) {
            var y = drops[i] * fontSize;
            for (var t = 0; t < 7; t++) {
              var ty = y - t * fontSize;
              if (ty < 0 || ty > canvas.height) continue;
              ctx.fillStyle = t === 0 ? 'rgba(187,247,208,0.95)' : ('rgba(0,255,65,' + Math.max(0.08, 0.72 - t * 0.1) + ')');
              ctx.fillText(chars[Math.floor(Math.random() * chars.length)], xs[i], ty);
            }
            if (y > canvas.height + 60 && Math.random() > 0.9) drops[i] = 0;
            drops[i]++;
          }
        }
        matrixRafId = requestAnimationFrame(draw);
      }
      matrixRafId = requestAnimationFrame(draw);
    };
    if (document.body) mount();
    else window.addEventListener('DOMContentLoaded', mount, { once: true });
  }

  function applyColorTheme(id) {
    var valid = COLOR_THEMES.some(function (t) { return t.id === id; }) ? id : 'default';
    try { localStorage.setItem('ag_color_theme', valid); } catch (e) {}
    if (valid === 'default') {
      document.documentElement.removeAttribute('data-ag-color-theme');
      document.documentElement.style.colorScheme = theme;
      stopMatrixEdgeCanvas();
    } else {
      document.documentElement.setAttribute('data-ag-color-theme', valid);
      document.documentElement.style.colorScheme = valid.endsWith('-light') ? 'light' : 'dark';
      if (valid === 'matrix') startMatrixEdgeCanvas();
      else stopMatrixEdgeCanvas();
    }
    return valid;
  }

  var savedColorTheme = 'default';
  try { savedColorTheme = localStorage.getItem('ag_color_theme') || 'default'; } catch (e) {}
  applyColorTheme(savedColorTheme);

  window.__AG_COLOR_THEMES__ = COLOR_THEMES;
  window.__AG_SET_COLOR_THEME__ = applyColorTheme;
})();

