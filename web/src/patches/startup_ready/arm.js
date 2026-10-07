/**
 * patches/startup_ready/arm.js - watch for the first real UI paint.
 */
function arm() {
  if (!global.document) {
    signal('no-document');
    return;
  }

  if (isUiRendered()) return;

  try {
    if (global.MutationObserver) {
      var observer = new MutationObserver(function () {
        if (isUiRendered()) {
          try { observer.disconnect(); } catch (e) {}
        }
      });
      var target = global.document.getElementById('root') || global.document.body || global.document.documentElement;
      if (target) {
        observer.observe(target, { childList: true, subtree: true });
      }
    }
  } catch (e) {}

  var poller = global.setInterval(function () {
    if (isUiRendered() || state.signalled) {
      global.clearInterval(poller);
    }
  }, 40);

  // Fallback: 5.5 seconds maximum waiting time
  global.setTimeout(function () {
    global.clearInterval(poller);
    if (isUiRendered()) {
      return;
    }
    signal('fallback-timeout');
  }, 5500);
}
