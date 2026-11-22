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
