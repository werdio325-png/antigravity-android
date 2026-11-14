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
