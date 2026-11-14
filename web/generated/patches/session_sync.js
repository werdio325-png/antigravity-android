(function(){
/**
 * patches/session_sync/config.js - conversation state synchronization.
 *
 * The engine is the source of truth for conversations and agent progress. The
 * WebView must therefore never keep showing a transcript it captured before the
 * app went to the background: when the app comes back it has to prove its event
 * stream is still alive, and reconcile with the engine if it is not.
 *
 * Nothing here invents progress or replays user actions; the only recovery is a
 * document reload, which makes the app refetch authoritative state from the
 * engine (and re-derive the open conversation from its own persisted state).
 */
'use strict';

if (window.__agSessionSync && window.__agSessionSync.__installed) {
  return;
}

var SESSION_SYNC = {
  // Below this the app was barely backgrounded; a nudge is enough.
  minBackgroundMs: 3000,
  // Time allowed for a fresh socket after resume before we reload.
  probeDelayMs: 1500,
  // A socket that stays closed this long while visible is treated as dead.
  deadStreamAfterMs: 12000,
  // Reload budget: stops a reload loop if the engine itself is down.
  maxReloads: 3,
  reloadCooldownMs: 30000,
  hostResumeEvent: 'agy:host-resume'
};

/**
 * patches/session_sync/state.js - observable state for the sync patch.
 */
var syncState = {
  installed: false,
  openSockets: 0,
  everOpened: false,
  lastOpenAt: 0,
  lastCloseAt: 0,
  reloads: 0,
  lastReloadAt: 0,
  probeTimer: 0,
  watchdogTimer: 0,
  lastResumeAt: 0,
  lastBackgroundMs: 0
};

/**
 * patches/session_sync/ws-track.js - observe the app's event stream.
 *
 * The app opens its live stream with `new WebSocket(url)`. We wrap the
 * constructor to observe open/close without touching the transport: the returned
 * object is the real socket, existing listeners are untouched, and the prototype
 * and numeric constants are preserved. This is observation only - no wrapper
 * methods, no message interception, no replayed frames.
 */
function agNow() {
  return Date.now ? Date.now() : new Date().getTime();
}

function observeSocket(socket) {
  if (!socket || socket.__agObserved) return;
  try { socket.__agObserved = true; } catch (_) { return; }
  try {
    socket.addEventListener('open', function () {
      syncState.openSockets = Math.max(0, syncState.openSockets) + 1;
      syncState.everOpened = true;
      syncState.lastOpenAt = agNow();
      cancelDeadStreamWatchdog();
      console.info('[agSync] stream open');
    });
    socket.addEventListener('close', function () {
      syncState.openSockets = Math.max(0, syncState.openSockets - 1);
      syncState.lastCloseAt = agNow();
      console.info('[agSync] stream closed');
      scheduleDeadStreamWatchdog();
    });
    socket.addEventListener('error', function () {
      // 'close' follows an error for real sockets; the watchdog covers the case
      // where it does not.
      scheduleDeadStreamWatchdog();
    });
  } catch (_) {
    /* observation must never break the app's transport */
  }
}

function installSocketObserver() {
  var Native = window.WebSocket;
  if (!Native || Native.__agTracked || Native.__agSessionSyncPatched) return false;

  function Tracked(url, protocols) {
    var socket = protocols === undefined ? new Native(url) : new Native(url, protocols);
    observeSocket(socket);
    return socket;
  }
  Tracked.prototype = Native.prototype;
  ['CONNECTING', 'OPEN', 'CLOSING', 'CLOSED'].forEach(function (key) {
    if (typeof Native[key] !== 'undefined') Tracked[key] = Native[key];
  });
  Tracked.__agTracked = true;
  Tracked.__agNative = Native;
  window.WebSocket = Tracked;
  return true;
}

/**
 * patches/session_sync/snapshot.js - authoritative reconciliation.
 *
 * The engine exposes no event-replay cursor to the page, so missed progress
 * cannot be replayed. Reloading the document is the one recovery that is both
 * safe and exact: the app re-renders from engine state instead of from whatever
 * React tree survived in the WebView. It is bounded, cooled down, and only used
 * when the stream is provably not open.
 */
function sessionIsVisible() {
  try {
    return !document.visibilityState || document.visibilityState === 'visible';
  } catch (_) {
    return true;
  }
}

function dispatchResumeNudge() {
  try {
    window.dispatchEvent(new CustomEvent(SESSION_SYNC.hostResumeEvent, {
      detail: { backgroundMs: syncState.lastBackgroundMs }
    }));
  } catch (_) { /* older engines of the UI simply ignore it */ }
  try {
    window.dispatchEvent(new Event('focus'));
  } catch (_) { /* ignore */ }
  try {
    document.dispatchEvent(new Event('visibilitychange'));
  } catch (_) { /* ignore */ }
}

function reloadForResync(reason) {
  var now = agNow();
  if (!syncState.everOpened) {
    return false;
  }
  if (syncState.reloads >= SESSION_SYNC.maxReloads) {
    console.warn('[agSync] reload budget exhausted; leaving the page alone (' + reason + ')');
    return false;
  }
  if (syncState.lastReloadAt && now - syncState.lastReloadAt < SESSION_SYNC.reloadCooldownMs) {
    return false;
  }
  syncState.reloads += 1;
  syncState.lastReloadAt = now;
  console.info('[agSync] reconciling with engine (' + reason + ')');
  try {
    // A reload keeps the origin and localStorage, so the app restores the same
    // workspace/conversation, but refetches it from the engine.
    window.location.reload();
  } catch (_) {
    return false;
  }
  return true;
}

function scheduleDeadStreamWatchdog() {
  if (syncState.watchdogTimer || !syncState.everOpened) return;
  if (!sessionIsVisible()) return;
  syncState.watchdogTimer = setTimeout(function () {
    syncState.watchdogTimer = 0;
    if (syncState.openSockets > 0 || !sessionIsVisible()) return;
    reloadForResync('stream stayed closed while visible');
  }, SESSION_SYNC.deadStreamAfterMs);
}

function cancelDeadStreamWatchdog() {
  if (syncState.watchdogTimer) {
    clearTimeout(syncState.watchdogTimer);
    syncState.watchdogTimer = 0;
  }
}

/**
 * patches/session_sync/resume.js - what happens when the host resumes.
 *
 * Fast path: the stream is still open, so the page shows live state - just nudge
 * the app to re-read anything it cached.
 *
 * Slow path: the stream died while backgrounded (WebView timers paused, socket
 * dropped by the OS, engine restarted). Give the app a moment to reconnect, and
 * reconcile from the engine if it cannot.
 */
function sessionOnResume(payload) {
  var backgroundMs = (payload && payload.backgroundMs) || 0;
  syncState.lastResumeAt = agNow();
  syncState.lastBackgroundMs = backgroundMs;

  if (backgroundMs < SESSION_SYNC.minBackgroundMs) {
    return { action: 'ignored', backgroundMs: backgroundMs };
  }
  if (!syncState.everOpened) {
    // Pages that never streamed (auth, setup) have nothing to reconcile.
    return { action: 'not-streaming', backgroundMs: backgroundMs };
  }

  dispatchResumeNudge();

  if (syncState.openSockets > 0) {
    return { action: 'nudged', backgroundMs: backgroundMs, openSockets: syncState.openSockets };
  }

  if (syncState.probeTimer) {
    return { action: 'pending', backgroundMs: backgroundMs };
  }
  syncState.probeTimer = setTimeout(function () {
    syncState.probeTimer = 0;
    if (syncState.openSockets > 0) {
      console.info('[agSync] stream reconnected on its own');
      return;
    }
    reloadForResync('stream closed across resume');
  }, SESSION_SYNC.probeDelayMs);
  return { action: 'reconnecting', backgroundMs: backgroundMs };
}

function installResumeHandlers() {
  window.addEventListener(SESSION_SYNC.hostResumeEvent, function (event) {
    var detail = (event && event.detail) || {};
    // The host already sent an explicit resume payload through onResume(); this
    // listener only covers paths where it did not (e.g. activity recreated).
    if (detail.backgroundMs === undefined) {
      if (syncState.openSockets === 0 && syncState.everOpened) scheduleDeadStreamWatchdog();
      return;
    }
  });
  document.addEventListener('visibilitychange', function () {
    if (sessionIsVisible()) {
      if (syncState.openSockets === 0 && syncState.everOpened) scheduleDeadStreamWatchdog();
    } else {
      cancelDeadStreamWatchdog();
    }
  });
}

/**
 * patches/session_sync/install.js - arm the patch.
 */
function installSessionSync() {
  if (syncState.installed) return;
  syncState.installed = true;
  window.__agSessionSync = window.__agSessionSync || {};
  window.__agSessionSync.__installed = true;
  window.__agSessionSync.onResume = sessionOnResume;

  var tracked = installSocketObserver();
  installResumeHandlers();

  console.info('[agSync] installed; socket observer=' + (tracked ? 'on' : 'unavailable'));
}

installSessionSync();

/**
 * patches/session_sync/public-api.js - window.AgySync diagnostics.
 */
window.AgySync = window.AgySync || {};

window.AgySync.state = function () {
  return {
    openSockets: syncState.openSockets,
    everOpened: syncState.everOpened,
    reloads: syncState.reloads,
    lastBackgroundMs: syncState.lastBackgroundMs,
    streamAlive: syncState.openSockets > 0
  };
};

/** Manual reconcile, used by the device test matrix and support tooling. */
window.AgySync.resync = function (reason) {
  return reloadForResync(reason || 'manual');
};

window.AgySync.resume = function (backgroundMs) {
  return sessionOnResume({ backgroundMs: backgroundMs || 0 });
};


})();
