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
  maxWaitMs: 600,
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
  scanTimer: 0,
  cancelPendingOpen: null
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
 *
 * Uses a bounded MutationObserver so submenu opening reacts immediately when
 * Base UI mounts/focuses elements under variable CPU load, while keeping
 * bounded fallback timers as a safety net.
 */
function isRowReadyForKey(row) {
  if (!row || !row.isConnected) return false;
  try {
    if (document.activeElement === row) return true;
    if (row.hasAttribute('data-highlighted')) return true;
    if (row.getAttribute('aria-expanded') === 'true') return true;
    if (row.getAttribute('tabindex') === '0') return true;
  } catch (_) {}
  return false;
}

function openSubmenu(row) {
  var now = Date.now();
  if (now - state.lastOpenAt < CONFIG.reopenGuardMs) return;
  state.lastOpenAt = now;
  state.opens++;

  if (typeof state.cancelPendingOpen === 'function') {
    state.cancelPendingOpen();
  }

  focusQuietly(row);
  dispatchPointerSequence(row);

  if (optionsOpen()) return;

  var done = false;
  var observer = null;
  var timers = [];
  var arrowSentOnMutation = false;

  function cleanup() {
    if (done) return;
    done = true;
    if (observer) {
      try { observer.disconnect(); } catch (_) {}
      observer = null;
    }
    for (var i = 0; i < timers.length; i++) {
      clearTimeout(timers[i]);
    }
    timers.length = 0;
    if (row) {
      try { row.removeEventListener('focus', onFocus, true); } catch (_) {}
    }
    if (state.cancelPendingOpen === cleanup) {
      state.cancelPendingOpen = null;
    }
  }

  function tryArrow() {
    if (done) return;
    if (optionsOpen()) {
      cleanup();
      return;
    }
    if (!row || !row.isConnected) {
      cleanup();
      return;
    }
    focusQuietly(row);
    arrowRight(row);
  }

  function onFocus() {
    if (done) return;
    if (optionsOpen()) {
      cleanup();
      return;
    }
    if (!arrowSentOnMutation) {
      arrowSentOnMutation = true;
      arrowRight(row);
    }
  }

  state.cancelPendingOpen = cleanup;

  if (row) {
    try { row.addEventListener('focus', onFocus, true); } catch (_) {}
  }

  if (window.MutationObserver) {
    var observeRoot = document.body || document.documentElement;
    if (observeRoot) {
      try {
        observer = new MutationObserver(function () {
          if (done) return;
          if (optionsOpen()) {
            cleanup();
            return;
          }
          if (!row || !row.isConnected) {
            cleanup();
            return;
          }
          if (!arrowSentOnMutation && isRowReadyForKey(row)) {
            arrowSentOnMutation = true;
            arrowRight(row);
          }
        });
        observer.observe(observeRoot, {
          childList: true,
          subtree: true,
          attributes: true,
          attributeFilter: ['data-highlighted', 'data-popup-open', 'aria-expanded', 'tabindex']
        });
      } catch (_) {
        observer = null;
      }
    }
  }

  // Bounded fallback timers as a safety net when no DOM mutation precedes key open.
  timers.push(setTimeout(tryArrow, CONFIG.fallbackDelayMs));
  timers.push(setTimeout(tryArrow, CONFIG.retryDelayMs));
  timers.push(setTimeout(cleanup, CONFIG.maxWaitMs || 600));
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

/**
 * patches/touch_model_menu/install.js - arm the observer on touch devices.
 */
function install() {
  if (state.installed) return;
  state.hasTouch = detectTouch();
  state.installed = true;

  if (!state.hasTouch) {
    console.log('[agTouchModelMenu] no touch input detected; patch is a no-op.');
    return;
  }

  scan();

  var root = document.querySelector(CONFIG.root) || document.body || document.documentElement;
  if (window.MutationObserver && root) {
    state.observer = new MutationObserver(scheduleScan);
    state.observer.observe(root, { childList: true, subtree: true });
  }
  console.log('[agTouchModelMenu] active (rows=' + state.rows + ').');
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
