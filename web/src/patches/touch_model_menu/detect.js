/**
 * patches/touch_model_menu/detect.js - input and submenu detection.
 */
function detectTouch() {
  try {
    if (navigator.maxTouchPoints > 0) return true;
    if ('ontouchstart' in window) return true;
    if (window.matchMedia && window.matchMedia('(pointer: coarse)').matches) return true;
    if (window.matchMedia && window.matchMedia('(any-pointer: coarse)').matches) return true;
  } catch (_) { /* ignore */ }
  return false;
}

function optionsOpen() {
  try {
    return document.querySelectorAll(CONFIG.effortOption).length > 0;
  } catch (_) {
    return false;
  }
}

function focusQuietly(el) {
  try { el.focus({ preventScroll: true }); } catch (_) {}
}
