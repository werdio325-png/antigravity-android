(function(){
/**
 * patches/touch_model_menu/config.js - config, guard and bundle scope.
 *
 * First fragment: if the patch already installed, exit the whole IIFE.
 */
'use strict';

if (window.__agTouchModelMenu && window.__agTouchModelMenu.__installed) {
  return;
}

var CONFIG = {
  // selectors
  trigger: '[data-testid="model-selector-trigger"]',
  panel: '[data-testid="model-selector-panel"]',
  effortGroup: '[data-testid="model-selector-effort-group"]',
  effortOption: '[data-testid="model-selector-effort-option"]',
  modelItem: '[data-testid="model-selector-item"]',
  menuItemRole: '[role="menuitem"]',
  // observer root
  root: '#root',
  // behaviour
  fallbackDelayMs: 70,
  retryDelayMs: 260,
  reopenGuardMs: 400
};

/**
 * patches/touch_model_menu/state.js - mutable patch state.
 */
var state = {
  installed: false,
  hasTouch: false,
  rows: 0,
  groups: 0,
  lastOpenAt: 0,
  opens: 0,
  observer: null,
  scanTimer: 0
};

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

/**
 * patches/touch_model_menu/synth.js - synthetic pointer/mouse/key sequences.
 */
function dispatchPointerSequence(el) {
  var rect;
  try { rect = el.getBoundingClientRect(); } catch (_) { rect = null; }
  var cx = rect ? rect.left + rect.width / 2 : 0;
  var cy = rect ? rect.top + rect.height / 2 : 0;

  var pointerInit = {
    bubbles: true, cancelable: true, composed: true,
    clientX: cx, clientY: cy, pointerId: 1, pointerType: 'touch',
    isPrimary: true, button: 0, buttons: 0
  };
  var mouseInit = {
    bubbles: true, cancelable: true, composed: true,
    clientX: cx, clientY: cy, button: 0, buttons: 0
  };

  function fire(make) {
    try {
      var ev = make();
      ev.__agTouchSynthetic = true;
      el.dispatchEvent(ev);
    } catch (_) { /* PointerEvent may be unavailable */ }
  }

  fire(function () { return new PointerEvent('pointerover', pointerInit); });
  fire(function () { return new PointerEvent('pointerenter', pointerInit); });
  fire(function () { return new PointerEvent('pointermove', pointerInit); });
  fire(function () { return new MouseEvent('mouseover', mouseInit); });
  fire(function () { return new MouseEvent('mouseenter', mouseInit); });
  fire(function () { return new MouseEvent('mousemove', mouseInit); });
}

function arrowRight(el) {
  try {
    var ev = new KeyboardEvent('keydown', {
      key: 'ArrowRight', code: 'ArrowRight', keyCode: 39, which: 39,
      bubbles: true, cancelable: true, composed: true
    });
    ev.__agTouchSynthetic = true;
    el.dispatchEvent(ev);
  } catch (_) {}
}

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

/**
 * patches/touch_model_menu/register.js - capture-phase row handlers.
 */
function makeHandler(row) {
  return function (event) {
    if (event.__agTouchSynthetic) return;
    if (typeof event.button === 'number' && event.button !== 0) return;
    // Cancel the select-default in the capture phase, before React sees it.
    event.preventDefault();
    event.stopPropagation();
    event.stopImmediatePropagation();
    openSubmenu(row);
  };
}

function registerRow(row) {
  if (!row || row.__agTouchManaged) return false;
  row.__agTouchManaged = true;
  var handler = makeHandler(row);
  row.addEventListener('pointerdown', handler, true);
  row.addEventListener('mousedown', handler, true);
  row.addEventListener('click', handler, true);
  state.rows++;
  return true;
}

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

/**
 * patches/touch_model_menu/install.js - arm on-demand when model selector opens.
 */
function install() {
  if (state.installed) return;
  if (!document || !document.addEventListener) return;
  state.installed = true;

  if (!state.hasTouch) {
    return;
  }

  var onInteract = function (ev) {
    var t = ev && ev.target;
    if (!t || !t.closest) return;
    if (t.closest(CONFIG.trigger) || t.closest(CONFIG.panel) || isPanelOpen()) {
      armPanelObserver();
    }
  };

  document.addEventListener('pointerdown', onInteract, { capture: true, passive: true });
  document.addEventListener('click', onInteract, { capture: true, passive: true });

  if (isPanelOpen()) {
    armPanelObserver();
  }
}

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


})();
