/**
 * core/online-and-notifications.js - Fix false "No internet" banner in Android
 * WebView and polyfill Web Notifications API.
 */
try {
  if (typeof Navigator !== 'undefined' && Navigator.prototype) {
    Object.defineProperty(Navigator.prototype, 'onLine', {
      get: function () { return true; },
      configurable: true
    });
  }
  var origAddEventListener = global.addEventListener ? global.addEventListener.bind(global) : null;
  if (origAddEventListener) {
    global.addEventListener = function (type, listener, options) {
      if (type === 'offline') {
        return;
      }
      return origAddEventListener(type, listener, options);
    };
  }
} catch (e) { warn('online-shim', e); }

try {
  var FakeNotification = function (title, options) {
    this.title = String(title || 'Antigravity');
    this.body = (options && options.body) ? String(options.body) : '';
    this.tag = (options && options.tag) ? String(options.tag) : ('agy_' + Date.now());
    this.silent = !!(options && options.silent);
    this.onclick = null;
    this.onclose = null;
  };
  FakeNotification.permission = 'granted';
  FakeNotification.requestPermission = function (cb) {
    if (typeof cb === 'function') {
      try { cb('granted'); } catch (e) {}
    }
    return Promise.resolve('granted');
  };
  FakeNotification.prototype.close = function () {
    if (typeof this.onclose === 'function') {
      try { this.onclose(); } catch (e) {}
    }
  };
  global.Notification = FakeNotification;
} catch (e) { warn('notification-polyfill', e); }
