// Fast theme bootstrap. Runs before the app bundle so the first paint already
// uses the host-selected color scheme. Fixed: honor "light" instead of always
// forcing the dark class.
(function () {
  'use strict';

  var theme = 'dark';
  try {
    var params = new URLSearchParams(window.location.search);
    theme = params.get('hostTheme') || window.__AG_HOST_THEME__ || 'dark';
  } catch (e) {
    theme = window.__AG_HOST_THEME__ || 'dark';
  }
  theme = theme === 'light' ? 'light' : 'dark';

  document.documentElement.style.colorScheme = theme;
  document.documentElement.classList.add(theme);
})();
