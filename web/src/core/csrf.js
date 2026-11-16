/**
 * core/csrf.js - CSRF token resolution and cookie sync.
 *
 * Sources (first hit wins): explicit global, meta tag, generated fallback.
 * Publishes `csrfToken`/`csrf_token` cookies that main.js and native read.
 */
var lastSyncedCookieToken = null;

function readCsrf() {
  try {
    if (window.__AG_CSRF__) return String(window.__AG_CSRF__);
    if (CONFIG.csrfToken) return String(CONFIG.csrfToken);
    var m = document.querySelector('meta[name="csrf-token"], meta[name="csrfToken"]');
    if (m && m.getAttribute('content')) return m.getAttribute('content');
  } catch (e) {}
  return CONFIG.csrfToken || 'agy-standalone-token';
}

function syncCsrf() {
  var token = readCsrf();
  CONFIG.csrfToken = token;
  CONFIG.csrf_token = token;
  if (token !== lastSyncedCookieToken) {
    try {
      if (document && document.cookie !== undefined) {
        document.cookie = 'csrfToken=' + token + '; path=/; SameSite=Lax';
        document.cookie = 'csrf_token=' + token + '; path=/; SameSite=Lax';
        lastSyncedCookieToken = token;
      }
    } catch (e) {}
  }
  return token;
}

syncCsrf();
