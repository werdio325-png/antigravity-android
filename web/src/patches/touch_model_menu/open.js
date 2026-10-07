/**
 * patches/touch_model_menu/open.js - open the effort submenu on tap.
 */
function openSubmenu(row) {
  var now = Date.now();
  if (now - state.lastOpenAt < CONFIG.reopenGuardMs) return;
  state.lastOpenAt = now;
  state.opens++;

  focusQuietly(row);
  dispatchPointerSequence(row);

  // Fallback: ArrowRight opens a focused Base UI submenu trigger.
  setTimeout(function () {
    if (!optionsOpen()) arrowRight(row);
  }, CONFIG.fallbackDelayMs);
  setTimeout(function () {
    if (!optionsOpen()) arrowRight(row);
  }, CONFIG.retryDelayMs);
}
