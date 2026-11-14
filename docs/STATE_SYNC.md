# Conversation state synchronization (3.1)

Why the app could show an older conversation after reopening, what changed, and
what is still device-dependent.

## Root causes (evidence, not guesses)

**1. Static assets were cached as immutable under stable filenames.**
`LocalAssetServer.intercept` answered every local asset with
`Cache-Control: public, max-age=31536000, immutable`. The filenames never change
between releases (`main.js`, `generated/patches/*.js`), so after an update the
WebView was entitled to keep rendering the *previous* release's bundle from its
HTTP cache without ever revalidating. `CoreInstaller` correctly re-extracts
`filesDir/web` when the APK's `lastUpdateTime` is newer, but that only changes the
file on disk — it cannot invalidate a cached response for the same URL. This is
the "the app shows older work" class of bug after an update.

**2. Nothing asked for fresh state when the activity resumed.**
`MainActivity.onResume` only re-applied the theme and restarted the readiness
poll. The readiness poll (`watchCore`) reloads the UI only when
`CoreRuntimeState.readyGeneration` changes, i.e. when the engine restarted. In the
normal case — app backgrounded, engine still alive, activity resumed — the WebView
kept its React tree and whatever transcript it had rendered before, and no
reconciliation with the engine happened at all. `WebViewLifecycle.pause` even
pauses page timers while backgrounded, so a stream that dropped was not noticed
until something touched the page.

**3. The live event stream had no liveness check or recovery.**
The UI streams over a WebSocket to the engine. If that socket is dropped across a
background/foreground cycle, the page keeps rendering its last known state.
Nothing detected "the stream is closed but the UI is on screen", and nothing
re-established an authoritative view. That is exactly the reported symptom: the
user had to kill and reopen the app, which forced a document reload and therefore
a fresh fetch from the engine.

The engine is authoritative throughout: conversations and progress live in it, the
page is a view. Every part of the fix follows from that.

## What changed

### Asset freshness
* `AssetCachePolicy` + `LocalAssetServer`: responses now carry
  `Cache-Control: no-cache, must-revalidate` and a validator
  (`ETag` = mtime + length). Matches answer **304** from a local file stat, so
  nothing is re-downloaded; a changed file answers 200 immediately.
* `AppHtmlInjector` appends `?v=<version>-<code>` to every local `src`/`href`, so
  even a WebView that ignored the headers cannot reuse a previous release's URL.

### Resume-time reconciliation
* `ui/lifecycle/SessionSync` records `onPause` time and, on resume, sends the page
  the facts: background duration, engine alive, app and engine versions.
* `web/src/patches/session_sync` observes the app's WebSocket **without altering
  it** (constructor wrapper returns the real socket; prototype and constants are
  preserved, no frames are intercepted). It then decides:
  - background < 3 s → ignore;
  - page never streamed (auth/setup) → ignore;
  - stream still open → nudge only (`agy:host-resume`, `focus`,
    `visibilitychange`); **never reload**;
  - stream closed → give it 1.5 s to reconnect, otherwise reconcile by reloading
    the document, which re-renders from engine state and keeps the same origin,
    `localStorage` and therefore the same workspace/conversation;
  - socket closed while visible (mid-session drop) → watchdog reloads after 12 s.
* Reloads are bounded: 30 s cooldown, max 3 per page, and only on pages that had
  a live stream. A dead engine cannot cause a reload loop.

### Why a reload instead of "fetch missed events"
The page is not given an event cursor or replay API by the engine, so missed
progress cannot be re-fetched event by event. Reloading is the only exact
reconciliation available: the app re-reads authoritative state instead of patching
a possibly stale tree. This is a deliberate, documented limitation — if the engine
ever exposes a cursor, `session_sync` is the single place to resume from it.

### No duplicated work
Nothing in the sync path submits, retries or replays a user or agent action. The
only mutation is a document reload, which the app treats as a fresh view of the
same conversation.

## Lifecycle behaviour now

| Event | Behaviour |
| :--- | :--- |
| Short background (<3 s) | Nothing; page keeps running |
| Resume, stream alive | Nudge only, no reload, no re-render from scratch |
| Resume, stream dead | Reconnect grace, then one reconciliation reload |
| Socket dies mid-session | Watchdog reload after 12 s if it stays closed |
| Engine restarted | `watchCore` sees a new `readyGeneration` and reloads the UI |
| Engine failed to start | Fatal splash with the real error (unchanged) |
| Process killed, app reopened | New engine + fresh document; state comes from the engine |
| Update installed | New `?v=` URLs + revalidating cache ⇒ new bundle is used |

## Background execution, stated honestly

The engine runs in `CoreServerService`, a foreground service in the app process
with a wake lock and a persistent notification. Removing the app from recents does
not normally kill a foreground service, so a running task usually continues. If
Android does kill the process, the task does **not** continue, and the UI must not
claim otherwise: on reopen the engine starts fresh and the conversation shows
whatever the engine actually persisted. There is no fabricated "still running"
state anywhere in this fork.

## Tests

`web/tests/sync_test.js` runs the real generated bundle in a sandbox with a fake
WebSocket, clock and reload counter (10 assertions, run by `web/tests/selftest.sh`
and therefore by CI):

* installs and exposes `window.AgySync`;
* resume with no stream never reloads (auth pages safe);
* short background ignored;
* live stream ⇒ nudge, no reload;
* dead stream ⇒ exactly one reconciliation reload after the grace period;
* self-reconnect cancels the pending reload;
* repeated resumes inside the cooldown reload at most once;
* watchdog reconciles a mid-session drop;
* reload budget caps a dead engine at 3 attempts;
* the WebSocket wrapper preserves prototype and constants.

Mapping to the requested matrix: **E** (connection interruption) and **F**
(duplicate prevention) are covered by these tests at the logic level; **A–D, G,
H** also depend on device behaviour and are listed in `docs/IMPLEMENTATION_PLAN.md`.

## Device verification (Galaxy A54, Android 16)

1. Open a conversation, start a task, let it produce several steps.
2. Home button, wait ~30 s, return → the transcript must show the newest steps
   within a second or two, without the app restarting.
3. Repeat, but remove the app from recents, then reopen → the view must match the
   engine's persisted state; if the task is gone, it must not look like it is still
   running.
4. Toggle airplane mode during a task, restore it → the watchdog (or the resume
   path) brings the view back in sync without duplicate messages.
5. Switch workspaces repeatedly → progress must never appear in the wrong
   conversation.
6. Install a newer build over this one → the first launch must use the new bundle
   (visible in `window.AgyCompat` versions / Settings).
