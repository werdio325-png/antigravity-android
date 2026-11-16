(function(){
/**
 * core/namespace.js - bridge.core bundle scope.
 *
 * Runs first in the bridge.core.js IIFE. Declares the shared scope every other
 * core fragment attaches to: `global`, `CONFIG`, `VERSION`, `warn`, plus the
 * idempotency guard. Window globals stay compatible with the original
 * (`window.__agBridgeCore` is the public API, `window.__AG_BRIDGE_CORE__` the
 * guard).
 */
'use strict';

var AG = (window.__agCore = window.__agCore || {});

if (window.__AG_BRIDGE_CORE__) return;
window.__AG_BRIDGE_CORE__ = true;

var global = window;
var VERSION = '1.2.1';

// Live config: filled by config.js, then kept in sync by csrf.js/endpoints.js.
var CONFIG = window.__APP_CONFIG__ || {};

function warn(where, err) {
  try {
    console.warn('[AGY bridge.core] ' + where + ':', err && err.message ? err.message : err);
  } catch (e) {}
}

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

/**
 * core/polyfills-process.js - window.process polyfill.
 *
 * main.js assumes an Electron-ish `process`. Provide a POSIX-shaped stub
 * without clobbering a real one.
 */
try {
  if (!window.process || typeof window.process !== 'object') window.process = {};
  var p = window.process;
  if (!p.platform) p.platform = 'linux';
  if (!p.arch) p.arch = 'arm64';
  if (!p.type) p.type = 'renderer';
  p.browser = p.browser !== undefined ? p.browser : true;
  if (!p.env || typeof p.env !== 'object') p.env = {};
  if (!p.versions || typeof p.versions !== 'object') p.versions = {};
  if (typeof p.cwd !== 'function') p.cwd = function () { return CONFIG.workspaceRoot || '/'; };
  if (typeof p.nextTick !== 'function') {
    p.nextTick = function (cb) {
      var args = Array.prototype.slice.call(arguments, 1);
      if (typeof queueMicrotask === 'function') queueMicrotask(function () { cb.apply(null, args); });
      else Promise.resolve().then(function () { cb.apply(null, args); });
    };
  }
} catch (e) { warn('process polyfill', e); }

/**
 * core/config.js - window.__APP_CONFIG__ fill.
 *
 * Fill only missing keys, then publish the merged object back and rebind
 * CONFIG so later fragments see the resolved values.
 */
var appConfig = {
  productName: 'Antigravity',
  appName: 'AGY',
  appVersion: VERSION,
  version: VERSION,
  isStandalone: true,
  platform: 'android',
  arch: 'arm64',
  enginePort: null,
  engineReady: false,
  apiPrefix: '',
  csrfToken: '',
  workspaceRoot: (CONFIG.workspaceRoot || (window.process && window.process.env && window.process.env.HOME) || '/storage/emulated/0'),
  activeCascadeId: null,
  devMode: false
};

try {
  var injected = window.__APP_CONFIG__ || {};
  for (var k in injected) {
    if (Object.prototype.hasOwnProperty.call(injected, k)) appConfig[k] = injected[k];
  }
  window.__APP_CONFIG__ = appConfig;
  CONFIG = appConfig;
} catch (e) { warn('__APP_CONFIG__', e); }

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

/**
 * core/endpoints.js - endpoint discovery + core origin resolution.
 *
 * Endpoints come from window.__AG_ENDPOINTS__ or the ?endpoints= query param.
 * `baseOrigin()` prefers a full injected URL, then a bare port mapped to
 * https. Also owns the CSRF header/query helpers shared with fetch-rewrite.
 */
function parseEndpointSpec(raw) {
  var out = {};
  if (!raw) return out;
  if (typeof raw === 'object') {
    for (var k in raw) { if (Object.prototype.hasOwnProperty.call(raw, k)) out[k] = String(raw[k]); }
    return out;
  }
  var s = String(raw).trim();
  if (!s) return out;
  // base64(JSON) or raw JSON
  if (s.charAt(0) === '{') {
    try { return parseEndpointSpec(JSON.parse(s)); } catch (e) {}
  }
  if (/^[A-Za-z0-9+/=]+$/.test(s) && s.length % 4 === 0) {
    try {
      var decoded = global.atob ? global.atob(s) : '';
      if (decoded && decoded.charAt(0) === '{') return parseEndpointSpec(JSON.parse(decoded));
    } catch (e) {}
  }
  // k=v,k2=v2  or k=v&k2=v2
  s.split(/[&,]/).forEach(function (pair) {
    var i = pair.indexOf('=');
    if (i > 0) {
      var k = decodeURIComponent(pair.slice(0, i).trim());
      var v = decodeURIComponent(pair.slice(i + 1).trim());
      if (k) out[k] = v;
    }
  });
  return out;
}

var ENDPOINTS = {};
try {
  ENDPOINTS = parseEndpointSpec(global.__AG_ENDPOINTS__);
  if (global.location && global.location.search) {
    var qp = new URLSearchParams(global.location.search);
    var q = qp.get('endpoints') || qp.get('ag_endpoints');
    if (q) {
      var fromQuery = parseEndpointSpec(q);
      for (var qk in fromQuery) { if (Object.prototype.hasOwnProperty.call(fromQuery, qk)) ENDPOINTS[qk] = fromQuery[qk]; }
    }
    var port = qp.get('port') || qp.get('enginePort');
    if (port) { ENDPOINTS.core_http = 'http://127.0.0.1:' + port; ENDPOINTS.enginePort = port; }
  }
} catch (e) { warn('endpoint parse', e); }
global.__AG_ENDPOINTS__ = ENDPOINTS;

function endpoint(name) {
  if (!name) return null;
  if (ENDPOINTS[name]) return ENDPOINTS[name];
  if (name === 'core' || name === 'core_http' || name === 'engine') return baseOrigin();
  return null;
}

function setEndpoint(name, value) {
  if (!name || value === undefined || value === null) return;
  ENDPOINTS[name] = String(value);
  global.__AG_ENDPOINTS__ = ENDPOINTS;
  return ENDPOINTS[name];
}

// The base origin of the core server. Prefer full injected URLs (the core
// serves ConnectRPC over TLS) over a bare port, which must map to https.
function baseOrigin() {
  if (CONFIG.engineBaseUrl) return String(CONFIG.engineBaseUrl).replace(/\/$/, '');
  if (ENDPOINTS.core) return String(ENDPOINTS.core).replace(/\/$/, '');
  if (ENDPOINTS.core_https) return String(ENDPOINTS.core_https).replace(/\/$/, '');
  if (CONFIG.apiPrefix) return String(CONFIG.apiPrefix).replace(/\/$/, '');
  if (ENDPOINTS.core_http) return String(ENDPOINTS.core_http).replace(/\/$/, '');
  if (CONFIG.enginePort) return 'https://127.0.0.1:' + CONFIG.enginePort;
  if (ENDPOINTS.enginePort) return 'https://127.0.0.1:' + ENDPOINTS.enginePort;
  return '';
}

function coreBase() {
  var p = (CONFIG.enginePort || ENDPOINTS.enginePort || (global.__APP_CONFIG__ && global.__APP_CONFIG__.enginePort) || (global.__AG_ENDPOINTS__ && global.__AG_ENDPOINTS__.enginePort) || 45157);
  return (baseOrigin() || ('https://127.0.0.1:' + p)).replace(/\/$/, '');
}

function ensureCsrf(headers) {
  try {
    if (headers && typeof headers.set === 'function'
        && typeof headers.has === 'function'
        && !headers.has('x-codeium-csrf-token')) {
      headers.set('x-codeium-csrf-token', syncCsrf());
    }
  } catch (e) {}
  return headers;
}

function appendCsrfQuery(url) {
  try {
    if (url.indexOf('csrf_token=') === -1) {
      url += (url.indexOf('?') === -1 ? '?' : '&')
          + 'csrf_token=' + encodeURIComponent(syncCsrf());
    }
  } catch (e) {}
  return url;
}

/**
 * core/resume-stream-recovery.js - Recover ConnectRPC streaming calls
 * (StreamAgentStateUpdates, JetboxSubscribeToState, etc.) after Android
 * WebViewLifecycle.pause() calls webView.pauseTimers().
 */
var activeStreamClosers = new Set();
var lastResumeTs = 0;
var lastHostResumeTs = 0;
var pendingVisibilityTimer = null;

// Fast-forward the 1000ms reconnect backoff in Hca / $i right after a host resume
try {
  var origSetTimeout = global.setTimeout.bind(global);
  var origClearTimeout = global.clearTimeout.bind(global);
  global.setTimeout = function (fn, delay) {
    if (delay === 1000 && (Date.now() - lastResumeTs) < 3000) {
      delay = 40;
    }
    var args = Array.prototype.slice.call(arguments, 2);
    return origSetTimeout(function () {
      fn.apply(global, args);
    }, delay);
  };
} catch (e) { warn('setTimeout fast-forward', e); }

function triggerStreamRecovery(reason, force) {
  try {
    var now = Date.now();
    if (!force && (now - lastResumeTs) < 300) {
      return;
    }
    lastResumeTs = now;
    if (activeStreamClosers.size > 0) {
      console.log('[AGY bridge.core] ' + reason + ': rotating ' + activeStreamClosers.size + ' active stream(s)');
      var entries = Array.from(activeStreamClosers);
      entries.forEach(function (entry) {
        if (entry && entry.openedAt <= now) {
          activeStreamClosers.delete(entry);
          try { entry.close(); } catch (e) {}
        }
      });
    }
    try { global.dispatchEvent(new Event('online')); } catch (e) {}
    try { global.dispatchEvent(new Event('focus')); } catch (e) {}
  } catch (e) { warn('triggerStreamRecovery', e); }
}

try {
  if (typeof origAddEventListener === 'function') {
    origAddEventListener('agy:host-resume', function () {
      lastHostResumeTs = Date.now();
      if (pendingVisibilityTimer) {
        try { origClearTimeout(pendingVisibilityTimer); } catch (e) {}
        pendingVisibilityTimer = null;
      }
      // Always rotate streams that were open before WebViewLifecycle.resume() finished
      triggerStreamRecovery('agy:host-resume', true);
    });
  }
  if (global.document && global.document.addEventListener) {
    global.document.addEventListener('visibilitychange', function () {
      if (global.document.visibilityState === 'visible') {
        if (pendingVisibilityTimer) {
          try { origClearTimeout(pendingVisibilityTimer); } catch (e) {}
        }
        pendingVisibilityTimer = origSetTimeout(function () {
          pendingVisibilityTimer = null;
          if ((Date.now() - lastHostResumeTs) > 500) {
            triggerStreamRecovery('visibilitychange:visible', false);
          }
        }, 150);
      }
    });
  }
} catch (e) { warn('resume listeners', e); }

function isStreamingRpc(pathOrUrl) {
  if (!pathOrUrl) return false;
  var s = String(pathOrUrl);
  return (s.indexOf('/exa.') !== -1 || s.indexOf('/jetski.') !== -1 || s.indexOf('Service/') !== -1)
      && (s.indexOf('Stream') !== -1 || s.indexOf('Subscribe') !== -1);
}

function isLoopbackRpcUrl(url) {
  if (!url) return false;
  var s = String(url);
  return /^https?:\/\/(127\.0\.0\.1|localhost)(:\d+)?\//i.test(s) && isStreamingRpc(s);
}

function wrapStreamingFetch(origFetch, url, opts) {
  var callerSignal = (opts && opts.signal) || (url && typeof url === 'object' && url.signal) || null;
  var streamCtrl = typeof AbortController !== 'undefined' ? new AbortController() : null;
  var rejectPendingRead = null;
  var closedByResume = false;
  var realReader = null;
  var entry = {
    openedAt: Date.now(),
    close: closeOnResume
  };

  function onCallerAbort() {
    activeStreamClosers.delete(entry);
    if (streamCtrl) {
      try { streamCtrl.abort(callerSignal && callerSignal.reason); } catch (e) {}
    }
    if (rejectPendingRead) {
      var r = rejectPendingRead;
      rejectPendingRead = null;
      try { r({ done: true, value: undefined }); } catch (e) {}
    }
  }

  function closeOnResume() {
    closedByResume = true;
    activeStreamClosers.delete(entry);
    if (rejectPendingRead) {
      var r = rejectPendingRead;
      rejectPendingRead = null;
      try { r({ done: true, value: undefined }); } catch (e) {}
    }
    if (realReader) {
      try { realReader.cancel('agy:host-resume'); } catch (e) {}
    }
    if (streamCtrl) {
      try { streamCtrl.abort(); } catch (e) {}
    }
  }

  if (callerSignal) {
    if (callerSignal.aborted) {
      onCallerAbort();
    } else if (callerSignal.addEventListener) {
      callerSignal.addEventListener('abort', onCallerAbort, { once: true });
    }
  }

  activeStreamClosers.add(entry);

  var fetchOpts = {};
  if (opts) {
    for (var k in opts) {
      if (Object.prototype.hasOwnProperty.call(opts, k)) fetchOpts[k] = opts[k];
    }
  }
  if (streamCtrl) {
    fetchOpts.signal = streamCtrl.signal;
  }

  return origFetch(url, fetchOpts).then(function (response) {
    if (!response || !response.body || typeof response.body.getReader !== 'function' || typeof ReadableStream === 'undefined') {
      return response;
    }
    var realBody = response.body;
    var wrappedStream = new ReadableStream({
      start: function () {
        realReader = realBody.getReader();
      },
      pull: function (controller) {
        if (closedByResume) {
          controller.close();
          return Promise.resolve();
        }
        var resumeInterrupter = new Promise(function (resolve) {
          rejectPendingRead = resolve;
        });
        return Promise.race([realReader.read(), resumeInterrupter]).then(function (res) {
          rejectPendingRead = null;
          if (!res || res.done || closedByResume) {
            activeStreamClosers.delete(entry);
            controller.close();
          } else {
            controller.enqueue(res.value);
          }
        }).catch(function (err) {
          rejectPendingRead = null;
          activeStreamClosers.delete(entry);
          if (closedByResume) {
            controller.close();
          } else {
            controller.error(err);
          }
        });
      },
      cancel: function (reason) {
        rejectPendingRead = null;
        activeStreamClosers.delete(entry);
        if (realReader) {
          try { return realReader.cancel(reason); } catch (e) {}
        }
      }
    });

    try {
      return new Response(wrappedStream, {
        status: response.status,
        statusText: response.statusText,
        headers: response.headers
      });
    } catch (e) {
      try {
        Object.defineProperty(response, 'body', {
          get: function () { return wrappedStream; },
          configurable: true
        });
      } catch (e2) {}
      return response;
    }
  }).catch(function (err) {
    activeStreamClosers.delete(entry);
    throw err;
  });
}

/**
 * core/fetch-rewrite.js - file:// WebView -> core origin fetch rewrite.
 *
 * The WebView loads from file:///android_asset/web/, so main.js builds its
 * transport from window.location.origin ("file://") and the Connect/grpc-web
 * transport then fetches e.g.
 *   file://exa.language_server_pb.languageserverservice/Method
 * which the WebView rejects: URL scheme "file" is not supported.
 *
 * Rewrite file:// or relative ConnectRPC URLs onto the core origin, preserving
 * method/headers/body and attaching the CSRF token. Idempotent and a no-op for
 * non-RPC / already-absolute http(s) URLs.
 *
 * The original periodic HEAD ping is not ported (it referenced an undefined
 * helper; see docs/DEADCODE.md).
 */
var RPC_MARKERS = ['exa.language_server_pb.', 'exa.host_bridge_pb.', '/exa.', '/jetski.'];

// RPC path (no leading slash) when `url` is a file:// or relative ConnectRPC
// URL; null for anything else (http(s), blob, data, static assets).
function rpcPathFrom(url) {
  if (!url) return null;
  var s = String(url);
  if (/^(https?|blob|data|wss?|chrome-extension):/i.test(s)) return null;
  var best = -1;
  for (var i = 0; i < RPC_MARKERS.length; i++) {
    var at = s.indexOf(RPC_MARKERS[i]);
    if (at >= 0 && (best === -1 || at < best)) best = at;
  }
  if (best === -1) return null;
  return s.slice(best).replace(/^\/+/, '') || null;
}

function installFetchRewrite() {
  if (global.__AG_FETCH_REWRITE__ || typeof global.fetch !== 'function') return;
  var origFetch = global.fetch.bind(global);

  function isAborted(err, opts) {
    if (opts && opts.signal && opts.signal.aborted) return true;
    if (!err) return false;
    return err.name === 'AbortError' || (typeof err.message === 'string' && err.message.indexOf('aborted') !== -1);
  }

  function fetchWithStartupRetry(u, opts, retries, isStream) {
    var doCall = isStream
      ? function () { return wrapStreamingFetch(origFetch, u, opts); }
      : function () { return origFetch(u, opts); };
    return doCall().catch(function (err) {
      if (isAborted(err, opts)) {
        throw err;
      }
      if (retries > 0) {
        return new Promise(function (resolve) {
          origSetTimeout(function () {
            resolve(fetchWithStartupRetry(u, opts, retries - 1, isStream));
          }, 120);
        });
      }
      throw err;
    });
  }

  global.fetch = function (resource, init) {
    try {
      // Request object: rebuild against the core origin, keep method/body.
      if (resource && typeof resource === 'object' && typeof resource.url === 'string') {
        var rpath = rpcPathFrom(resource.url);
        if (rpath) {
          try {
            var targetUrl = appendCsrfQuery(coreBase() + '/' + rpath);
            var reqMethod = resource.method || (init && init.method) || 'POST';
            var reqHeaders = new Headers(resource.headers || (init && init.headers) || {});
            ensureCsrf(reqHeaders);
            var reqSignal = (init && init.signal) || resource.signal;

            var bodyPromise;
            if (init && init.body !== undefined) {
              bodyPromise = Promise.resolve(init.body);
            } else {
              try {
                bodyPromise = resource.clone().arrayBuffer().then(function (buf) {
                  return buf.byteLength > 0 ? buf : undefined;
                }).catch(function () { return undefined; });
              } catch (e) {
                bodyPromise = Promise.resolve(undefined);
              }
            }

            return bodyPromise.then(function (bodyData) {
              var nextOpts = {
                method: reqMethod,
                headers: reqHeaders,
                body: bodyData,
                credentials: 'omit',
                signal: reqSignal
              };
              return fetchWithStartupRetry(targetUrl, nextOpts, 30, isStreamingRpc(rpath));
            });
          } catch (e) { warn('fetch rewrite (Request)', e); }
        }
        if (isLoopbackRpcUrl(resource.url)) {
          return wrapStreamingFetch(origFetch, resource, init);
        }
        return origFetch(resource, init);
      }

      // String URL: the Connect/grpc-web transport's common path.
      if (typeof resource === 'string') {
        var path = rpcPathFrom(resource);
        if (path) {
          var url = appendCsrfQuery(coreBase() + '/' + path);
          var headers = new Headers((init && init.headers) || {});
          ensureCsrf(headers);
          var next = {};
          if (init) {
            for (var k in init) {
              if (Object.prototype.hasOwnProperty.call(init, k)) next[k] = init[k];
            }
          }
          next.headers = headers;

          return fetchWithStartupRetry(url, next, 30, isStreamingRpc(path));
        }
        if (isLoopbackRpcUrl(resource)) {
          return wrapStreamingFetch(origFetch, resource, init);
        }
      }
    } catch (e) { warn('fetch rewrite', e); }
    return origFetch(resource, init);
  };
  global.__AG_FETCH_REWRITE__ = true;
}

installFetchRewrite();

/**
 * core/public-api.js - expose window.__agBridgeCore.
 *
 * Dead entries listed in docs/DEADCODE.md are not ported.
 */
var api = {
  version: VERSION,
  config: CONFIG,
  endpoints: ENDPOINTS,
  endpoint: endpoint,
  setEndpoint: setEndpoint,
  baseOrigin: baseOrigin,
  syncCsrf: syncCsrf,
  parseEndpointSpec: parseEndpointSpec,
  triggerStreamRecovery: triggerStreamRecovery,
  getActiveStreamCount: function () { return activeStreamClosers.size; }
};

window.__agBridgeCore = api;

try { console.log('[AGY bridge.core] v' + VERSION + ' ready (loopback stream recovery + online shim active)'); } catch (e) {}


})();
