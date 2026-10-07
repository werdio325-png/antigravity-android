/**
 * patches/touch_model_menu/scan.js - debounced DOM scan for effort groups.
 */

// Trailing debounce: DOM mutates constantly while a reply streams. A rAF scan
// would run at screen refresh (60-120 Hz); coalesce to one pass.
var SCAN_DEBOUNCE_MS = 180;

function scan() {
  var groups = [];
  try {
    groups = document.querySelectorAll(CONFIG.effortGroup);
  } catch (_) {
    groups = [];
  }
  state.groups = groups.length;
  for (var i = 0; i < groups.length; i++) {
    var group = groups[i];
    var row = (group.closest && group.closest(CONFIG.menuItemRole)) || group.parentElement || group;
    registerRow(row);
  }
}

function scheduleScan() {
  if (state.scanTimer) return;
  state.scanTimer = setTimeout(function () {
    state.scanTimer = 0;
    scan();
  }, SCAN_DEBOUNCE_MS);
}
