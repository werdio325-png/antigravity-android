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
