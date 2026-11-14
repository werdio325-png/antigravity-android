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
