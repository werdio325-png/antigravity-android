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
