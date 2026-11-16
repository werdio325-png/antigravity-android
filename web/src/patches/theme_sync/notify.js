/**
 * patches/theme_sync/notify.js - sync DOM classes, color-scheme, and native Android.
 */
function notify() {
  var bodyTheme = fromClassList();
  var theme = resolveTheme();
  var themeChanged = (theme !== state.last);
  var bodyChanged = (bodyTheme !== null && bodyTheme !== state.lastFromBody);
  if (!themeChanged && !bodyChanged) return theme;
  state.last = theme;
  if (bodyTheme !== null) state.lastFromBody = bodyTheme;
  state.notifications++;

  try {
    var isDark = (theme === 'dark');
    doc.documentElement.classList.toggle('dark', isDark);
    doc.documentElement.classList.toggle('light', !isDark);
    doc.documentElement.style.colorScheme = theme;
    updateColorSchemeMeta(theme);
  } catch (e) { /* ignore */ }

  try {
    if (window.Android) {
      state.hasAndroid = true;
      // Only persist explicit user preference from body class transitions after boot,
      // never overwrite Android's "system" mode during initial pre-React load.
      if (bodyChanged && state.notifications > 1 && !window.__agThemeExplicitSync) {
        if (typeof window.Android.setThemeByUser === 'function') {
          window.Android.setThemeByUser(bodyTheme);
        } else if (typeof window.Android.setTheme === 'function') {
          window.Android.setTheme(bodyTheme);
        }
      }
      if (typeof window.Android.onVisualThemeChanged === 'function') {
        window.Android.onVisualThemeChanged(theme);
      }
    }
  } catch (e) { /* never throw into JS */ }
  return theme;
}
