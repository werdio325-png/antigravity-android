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
})();
