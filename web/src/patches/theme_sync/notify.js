/**
 * patches/theme_sync/notify.js - push the resolved theme to native Android.
 */
function notify() {
  var theme = resolveTheme();
  if (theme === state.last) return theme;
  state.last = theme;
  state.notifications++;
  try {
    if (window.Android) {
      state.hasAndroid = true;
      if (typeof window.Android.onVisualThemeChanged === 'function') {
        window.Android.onVisualThemeChanged(theme);
      }
    }
  } catch (e) { /* never throw into JS */ }
  return theme;
}
