/**
 * patches/startup_ready/signal.js - shared scope, guard and ready signal.
 *
 * First fragment: if the patch already installed, exit the whole IIFE.
 */
'use strict';

if (window.__agStartupReady && window.__agStartupReady.__installed) {
  return;
}

var global = window;
var state = { signalled: false, inputSeenAt: 0 };

function signal(reason) {
  if (state.signalled) return;
  state.signalled = true;
  try {
    console.log('[AGY Startup] UI Ready triggered via: ' + (reason || 'unknown'));
    if (global.Android && typeof global.Android.onUiReady === 'function') {
      global.Android.onUiReady();
    }
  } catch (e) {
    console.warn('[AGY Startup] Android.onUiReady error:', e);
  }
}
