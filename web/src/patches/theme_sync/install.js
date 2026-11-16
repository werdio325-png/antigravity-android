/**
 * patches/theme_sync/install.js - observe class/storage changes immediately.
 */
function observeBody() {
  if (doc && doc.body && state.observer) {
    try {
      state.observer.observe(doc.body, {
        attributes: true, attributeFilter: ['class']
      });
      return true;
    } catch (e) { /* ignore */ }
  }
  return false;
}

function install() {
  if (state.installed) return;
  state.installed = true;

  if (window.MutationObserver && doc && doc.documentElement) {
    try {
      state.observer = new MutationObserver(function () { notify(); });
      state.observer.observe(doc.documentElement, {
        attributes: true, attributeFilter: ['class']
      });
      if (!observeBody() && doc.addEventListener) {
        doc.addEventListener('DOMContentLoaded', function () {
          observeBody();
          notify();
        }, { once: true });
      }
    } catch (e) { /* ignore */ }
  }

  try {
    if (!state.storageBound) {
      window.addEventListener('storage', function () { notify(); }, false);
      state.storageBound = true;
    }
    if (window.matchMedia) {
      var mql = window.matchMedia('(prefers-color-scheme: dark)');
      if (mql.addEventListener) {
        mql.addEventListener('change', function () { notify(); });
      }
    }
  } catch (e) { /* ignore */ }

  notify();
}
