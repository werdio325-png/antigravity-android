/**
 * patches/model_compat/install.js - arm the observer and expose the report.
 */
function scheduleScan() {
  if (state.timer) return;
  state.timer = setTimeout(function () {
    state.timer = 0;
    scanModelRows();
  }, MODEL_COMPAT.scanDebounceMs);
}

function installModelCompat() {
  if (state.installed) return;
  state.installed = true;

  var root = document.querySelector(MODEL_COMPAT.root) || document.body || document.documentElement;
  if (root && window.MutationObserver) {
    state.observer = new MutationObserver(scheduleScan);
    state.observer.observe(root, { childList: true, subtree: true, attributes: true,
      attributeFilter: ['aria-disabled', 'disabled', 'data-disabled', 'class'] });
  }
  // The menu only exists after the user opens it; scan once DOM is ready too.
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', scheduleScan);
  } else {
    scheduleScan();
  }
  console.log('[agModelCompat] active; engine=' + engineVersion() +
    ' catalog=' + engineCatalog().length + ' models');
}
