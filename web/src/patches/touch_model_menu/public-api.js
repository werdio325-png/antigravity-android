/**
 * patches/touch_model_menu/public-api.js - expose probe and start.
 */
window.__agTouchModelMenu = {
  __installed: true,
  config: CONFIG,
  probe: function () {
    var groups = 0;
    var options = 0;
    try { groups = document.querySelectorAll(CONFIG.effortGroup).length; } catch (_) {}
    try { options = document.querySelectorAll(CONFIG.effortOption).length; } catch (_) {}
    return {
      installed: state.installed,
      hasTouch: state.hasTouch,
      rows: state.rows,
      groups: groups,
      options: options,
      submenuOpen: options > 0,
      opens: state.opens
    };
  }
};

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', install);
} else {
  install();
}
