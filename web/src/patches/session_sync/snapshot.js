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
