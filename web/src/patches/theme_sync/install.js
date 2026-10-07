/**
 * patches/theme_sync/install.js - observe class/storage changes.
 */
function install() {
  if (state.installed) return;
  state.installed = true;

  if (window.MutationObserver && doc.documentElement) {
    try {
      state.observer = new MutationObserver(function () { notify(); });
      state.observer.observe(doc.documentElement, {
        attributes: true, attributeFilter: ['class']
      });
      if (doc.body) {
        state.observer.observe(doc.body, {
          attributes: true, attributeFilter: ['class']
        });
      }
    } catch (e) { /* ignore */ }
  }

  try {
    if (!state.storageBound) {
      window.addEventListener('storage', function () { notify(); }, false);
      state.storageBound = true;
    }
  } catch (e) { /* ignore */ }

  notify();
}
