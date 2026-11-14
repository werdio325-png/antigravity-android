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
