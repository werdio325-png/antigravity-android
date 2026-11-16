/**
 * patches/touch_model_menu/scan.js - debounced DOM scan for effort groups.
 */

var SCAN_DEBOUNCE_MS = 80;

function isPanelOpen() {
  try {
    return !!document.querySelector(CONFIG.panel);
  } catch (_) {
    return false;
  }
}

function disconnectObserver() {
  if (state.observer) {
    try { state.observer.disconnect(); } catch (_) {}
  }
}

function scan() {
  if (!isPanelOpen()) {
    state.groups = 0;
    disconnectObserver();
    return;
  }
  var groups = [];
  try {
    groups = document.querySelectorAll(CONFIG.effortGroup);
  } catch (_) {
    groups = [];
  }
  state.groups = groups.length;
  for (var i = 0; i < groups.length; i++) {
    enhanceGroup(groups[i]);
  }
}

function scheduleScan() {
  if (state.debounceTimer) clearTimeout(state.debounceTimer);
  state.debounceTimer = setTimeout(function () {
    state.debounceTimer = null;
    scan();
  }, SCAN_DEBOUNCE_MS);
}

function armPanelObserver() {
  var root = document.querySelector(CONFIG.root) || document.body || document.documentElement;
  if (window.MutationObserver && root) {
    if (!state.observer) {
      state.observer = new MutationObserver(scheduleScan);
    }
    try {
      state.observer.observe(root, { childList: true, subtree: true });
    } catch (_) {}
  }
  setTimeout(scan, 16);
  setTimeout(scan, 80);
  setTimeout(scan, 200);
}
