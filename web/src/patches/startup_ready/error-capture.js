/**
 * patches/startup_ready/error-capture.js - surface startup errors to native.
 */
var startupErrors = [];
try {
  global.addEventListener('error', function (ev) {
    var msg = ev && (ev.message || (ev.error && ev.error.message)) ? (ev.message || ev.error.message) : String(ev);
    var src = ev && ev.filename ? (ev.filename + ':' + ev.lineno + ':' + ev.colno) : '';
    var full = '[JS Error ' + src + '] ' + msg;
    startupErrors.push(full);
    console.error(full);
    if (!state.signalled && global.Android && typeof global.Android.onUiError === 'function') {
      global.Android.onUiError(full);
    }
  });
  global.addEventListener('unhandledrejection', function (ev) {
    var reason = ev && ev.reason ? (ev.reason.stack || ev.reason.message || String(ev.reason)) : 'unknown rejection';
    var full = '[Unhandled Rejection] ' + reason;
    startupErrors.push(full);
    console.error(full);
    if (!state.signalled && global.Android && typeof global.Android.onUiError === 'function') {
      global.Android.onUiError(full);
    }
  });
} catch (e) {}
