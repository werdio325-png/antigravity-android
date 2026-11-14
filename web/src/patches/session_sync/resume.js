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
