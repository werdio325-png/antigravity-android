/**
 * core/csrf.js - CSRF token resolution and cookie sync.
 *
 * Sources (first hit wins): explicit global, meta tag, generated fallback.
 * Publishes `csrfToken`/`csrf_token` cookies that main.js and native read.
 */
var generatedCsrfFallback = '';

function generateFallbackCsrf() {
  if (generatedCsrfFallback) return generatedCsrfFallback;
  try {
    var c = global.crypto || global.msCrypto;
    if (c && typeof c.randomUUID === 'function') {
      generatedCsrfFallback = c.randomUUID();
      return generatedCsrfFallback;
    }
    if (c && typeof c.getRandomValues === 'function') {
      var bytes = new Uint8Array(16);
      c.getRandomValues(bytes);
      var hex = '';
      for (var i = 0; i < bytes.length; i++) {
        var b = bytes[i].toString(16);
        hex += b.length < 2 ? '0' + b : b;
      }
      generatedCsrfFallback = hex;
      return generatedCsrfFallback;
    }
  } catch (e) {}
  var out = '';
  for (var j = 0; j < 32; j++) {
    out += Math.floor(Math.random() * 16).toString(16);
  }
  generatedCsrfFallback = out;
  return generatedCsrfFallback;
}

function readCsrf() {
  try {
    if (window.__AG_CSRF__) return String(window.__AG_CSRF__);
    var m = document.querySelector('meta[name="csrf-token"], meta[name="csrfToken"]');
    if (m && m.getAttribute('content')) return m.getAttribute('content');
  } catch (e) {}
  return CONFIG.csrfToken || generateFallbackCsrf();
}

function syncCsrf() {
  var token = readCsrf();
  CONFIG.csrfToken = token;
  CONFIG.csrf_token = token;
  try {
    if (document && document.cookie !== undefined) {
      document.cookie = 'csrfToken=' + token + '; path=/; SameSite=Lax';
      document.cookie = 'csrf_token=' + token + '; path=/; SameSite=Lax';
    }
  } catch (e) {}
  return token;
}

syncCsrf();
