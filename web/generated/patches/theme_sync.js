(function(){
/**
 * patches/theme_sync/config.js - host theme, state and guard.
 *
 * First fragment: if the patch already installed, exit the whole IIFE.
 */
'use strict';

if (window.__agThemeSync && window.__agThemeSync.__installed) {
  return;
}

var doc = window.document;
var initialHostTheme = 'system';
try {
  var params = new URLSearchParams(window.location.search);
  initialHostTheme = params.get('hostTheme') || window.__AG_HOST_THEME__ || 'system';
} catch (e) {
  initialHostTheme = window.__AG_HOST_THEME__ || 'system';
}
if (initialHostTheme !== 'light' && initialHostTheme !== 'dark') {
  initialHostTheme = 'system';
}

var state = {
  installed: false,
  last: null,
  lastFromBody: null,
  notifications: 0,
  observer: null,
  storageBound: false,
  hasAndroid: false
};

/**
 * patches/theme_sync/resolve.js - theme resolution (body classList > Android > host > media).
 */
function fromClassList() {
  try {
    var body = doc && doc.body;
    if (body && body.classList) {
      if (body.classList.contains('theme-light')) return 'light';
      if (body.classList.contains('theme-dark')) return 'dark';
    }
  } catch (e) { /* ignore */ }
  return null;
}

function resolveTheme() {
  var cl = fromClassList();
  if (cl) return cl;
  if (typeof window !== 'undefined' && window.Android && typeof window.Android.getTheme === 'function') {
    try {
      var t = window.Android.getTheme();
      if (t === 'dark' || t === 'light') return t;
    } catch (e) {}
  }
  if (initialHostTheme === 'light') return 'light';
  if (initialHostTheme === 'dark') return 'dark';
  if (typeof window !== 'undefined' && window.__AG_RESOLVED_THEME__) {
    return window.__AG_RESOLVED_THEME__;
  }
  if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
    return 'dark';
  }
  return 'light';
}

function updateColorSchemeMeta(theme) {
  try {
    if (!doc || !doc.head) return;
    var meta = doc.querySelector('meta[name="color-scheme"]');
    if (!meta) {
      meta = doc.createElement('meta');
      meta.setAttribute('name', 'color-scheme');
      doc.head.appendChild(meta);
    }
    meta.setAttribute('content', theme === 'light' ? 'only light' : 'dark');
  } catch (e) { /* ignore */ }
}

/**
 * patches/theme_sync/notify.js - sync DOM classes, color-scheme, and native Android.
 */
function notify() {
  var bodyTheme = fromClassList();
  var theme = resolveTheme();
  var themeChanged = (theme !== state.last);
  var bodyChanged = (bodyTheme !== null && bodyTheme !== state.lastFromBody);
  if (!themeChanged && !bodyChanged) return theme;
  state.last = theme;
  if (bodyTheme !== null) state.lastFromBody = bodyTheme;
  state.notifications++;

  try {
    var isDark = (theme === 'dark');
    doc.documentElement.classList.toggle('dark', isDark);
    doc.documentElement.classList.toggle('light', !isDark);
    doc.documentElement.style.colorScheme = theme;
    updateColorSchemeMeta(theme);
  } catch (e) { /* ignore */ }

  try {
    if (window.Android) {
      state.hasAndroid = true;
      // Only persist explicit user preference from body class transitions after boot,
      // never overwrite Android's "system" mode during initial pre-React load.
      if (bodyChanged && state.notifications > 1 && !window.__agThemeExplicitSync) {
        if (typeof window.Android.setThemeByUser === 'function') {
          window.Android.setThemeByUser(bodyTheme);
        } else if (typeof window.Android.setTheme === 'function') {
          window.Android.setTheme(bodyTheme);
        }
      }
      if (typeof window.Android.onVisualThemeChanged === 'function') {
        window.Android.onVisualThemeChanged(theme);
      }
    }
  } catch (e) { /* never throw into JS */ }
  return theme;
}

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

/**
 * patches/theme_sync/public-api.js - expose probe and start immediately.
 */
window.__agThemeSync = {
  __installed: true,
  getTheme: resolveTheme,
  sync: notify,
  probe: function () {
    return {
      installed: state.installed,
      theme: resolveTheme(),
      last: state.last,
      notifications: state.notifications,
      hostTheme: initialHostTheme,
      hasAndroid: state.hasAndroid,
      observers: state.observer ? 1 : 0
    };
  }
};

install();


})();
