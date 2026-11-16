/**
 * patches/theme_sync/public-api.js - expose probe and start immediately.
 */
window.__agThemeSync = {
  __installed: true,
  getTheme: resolveTheme,
  sync: notify,
  probe: function () {
    return {
      installed: state.installed,
      theme: resolveTheme(),
      last: state.last,
      notifications: state.notifications,
      hostTheme: initialHostTheme,
      hasAndroid: state.hasAndroid,
      observers: state.observer ? 1 : 0
    };
  }
};

install();
