## Continuum 3.1.0 — state synchronization, lifecycle reliability and engine audit

Reopening Continuum now shows the conversation the engine actually has, instead of
whatever the page happened to render before it was backgrounded.

### Fixed: stale conversations and missing progress
- **Cached bundles can no longer survive an update.** Local UI assets were served
  as `immutable` for a year under stable filenames, so a WebView could keep
  rendering the previous release's `main.js` after an update. Assets now revalidate
  (`ETag` + `no-cache`, 304 on match) and every local script/stylesheet URL carries
  the app version.
- **Resume now reconciles with the engine.** The native host reports background
  duration, engine liveness and versions to the page. If the live stream survived,
  the app is nudged and nothing is reloaded; if it died, the page gives it a short
  grace period and then reloads once from authoritative engine state.
- **Stream death is detected mid-session too:** a socket that stays closed while
  the app is visible triggers a single bounded reconciliation.
- Reloads are cooled down and capped (3 per page), only happen on pages that were
  streaming, and never submit, retry or replay an agent request — so no duplicate
  work and no fabricated progress.

### Lifecycle and performance
- Backgrounding pauses page timers as before; returning no longer tears down and
  rebuilds the WebView on every switch.
- The engine still runs in its foreground service with a wake lock. If Android
  kills the process, the app now reflects the engine's real state rather than
  implying a task kept running.

### Engine modernization audit
- Verified the vendored engine is still the newest legitimately obtainable build:
  upstream `werdio325-png/antigravity-android` ships the byte-identical archive
  (`sha256 a48ddf73…`) and has no newer commits. Engine `language_server` 2.11.0
  (`go1.28-20260921-RC02`), catalogue of 24 models including `gemini-4-argon`
  (low/medium/high).
- **Gemini 4 Hydrogen / Carbon remain unavailable and are not faked:** this engine
  build contains no Hydrogen model id and no model-shaped Carbon identifier, so no
  account or client change can offer them. Adopting a newer engine stays a
  reviewed, offline step (`modules/engine/upgrade_engine.py`); nothing is
  downloaded or executed unverified, and no entitlement is bypassed.

### Updates
- The update panel now also shows the embedded engine version and build tag next
  to the installed app version.

### Known limitations
- The engine exposes no event-replay cursor to the page, so reconciliation is a
  single authoritative reload rather than incremental replay (documented in
  `docs/STATE_SYNC.md`).
- Device behaviour on the Galaxy A54 (background/recents/process-kill scenarios)
  requires on-device verification; see the test matrix in `docs/IMPLEMENTATION_PLAN.md`.
