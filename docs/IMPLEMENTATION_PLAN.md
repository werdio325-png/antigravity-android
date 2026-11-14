# Implementation plan and status

Seven stages, each independently testable. Status reflects what was executed in
this repository, not what is expected to work.

Legend: **done** = implemented and verified here; **device** = implemented, needs
the Galaxy A54 to verify; **n/a** = deliberately not done.

## S0 — Audit (done)

Full tree inspection, engine provenance, model-availability root cause, limitations.
Output: [`docs/AUDIT.md`](AUDIT.md).

## S1 — Fork identity (done)

* Package renamed to `dev.continuum.antigravity` across 125 Java files and all
  scripts/docs (binaries and generated web bundles excluded).
* Manifest: new package, `versionCode 30`, component names, `${applicationId}.fileprovider`,
  `REQUEST_INSTALL_PACKAGES`, package-installer `<queries>` entry.
* Ports moved to 45167/45168 (engine) and 45170/45171 (gateway) so upstream and
  this fork can run at the same time with separate data directories.
* Standard-only build: the region-bypass edition is gone; `--region-bypass` exits
  non-zero instead of building a bypass APK.
* Separate signing key (`tools/gen_keys.sh`), `08_sign.sh` fails closed when the
  release key is missing, debug key only with `DEV_MODE=1`.

Verified: `01_gen_config.py` → `02_r_java.sh` → `03_compile_java.sh` → `04_dex.sh`
(196 classes) and a full signed APK build.

## S2 — Engine compatibility layer (done)

* `modules/engine/catalog.py` — two-phase catalogue extraction from the binary:
  standalone ids plus family-anchored tier recovery, label confirmation with
  boundary rules, bare-version pruning.
* `modules/engine/engine_probe.py` — ELF/arch, engine version, build tag, sha256,
  catalogue, and patch compatibility via uniform `dry_run` patch application.
* `modules/engine/upgrade_engine.py` — validate a candidate archive, diff its
  catalogue, report patch compatibility, and with `--apply` update the pristine
  archive, patch specs and the runtime hash pins. No downloads, ever.
* `prepare_runtime.py` writes `engine_info.json` into the assembled runtime and
  regenerates `manifest.json` so it is covered by the integrity manifest.
* Java: `engine/EngineInfo.java` reads it (installed runtime first, APK assets
  fallback) and `EngineConfigJson` exposes it as `__APP_CONFIG__.engineInfo`.
* Web: `web/src/patches/model_compat` annotates rows (`engine` / `account`) and
  exposes `window.AgyCompat`.

Verified here: probe on the real archive reports 2.11.0 /
`go1.28-20260921-RC02`, AArch64 ELF, **24 catalogue entries**, patches
`compatible`; APK contains `assets/runtime/engine_info.json` with the same data and
the runtime manifest covers 33 files.

## S3 — In-app updater (done, device for the installer UI)

Native: `update/UpdateChecker` (GitHub Releases API + SHA256SUMS parsing),
`UpdateDownloader` (HTTPS-only, size ceiling, streaming progress, SHA-256 verify,
delete on mismatch), `UpdateInstaller` (system package installer only),
`UpdateManager` (state machine: idle → checking → available → downloading → ready →
installing; postpone persisted; errors surfaced), `UpdateBridge` (token-gated JS
surface).

Web: `web/src/patches/updater_ui` — button, panel with installed/latest version,
changelog, size, checksum prefix, progress bar, Check/Download/Install/Later, and
the "allow install unknown apps" guidance.

Verified here: compiles, bundles, syntax-checked, packaged into the APK. The actual
download/install path needs a device (no network/installer in the build sandbox).

## S4 — Upstream sync (done)

`.github/workflows/upstream-sync.yml` — daily + manual; fetches upstream, merges in
a scratch branch, runs the test suites and fork-identity assertions, pushes the
branch and opens a **draft PR**; conflicts abort the merge and open an issue
listing the files. It never pushes to `main` and never auto-merges.

## S5 — Release automation (done)

`.github/workflows/build-and-release.yml` — tests → signed ARM64 build (release key
from secrets, fail closed) → `tools/verify_apk.sh` (package id, version, non-debuggable,
AArch64 ELF engine, required assets) → tag/version cross-check → `SHA256SUMS.txt` →
artifacts → GitHub Release with `continuum.apk` + `SHA256SUMS.txt`, which is exactly
what the updater reads.

## S6 — Docs and diagnostics (done)

`docs/AUDIT.md`, `docs/IMPLEMENTATION_PLAN.md`, `docs/BUILDING.md`,
`docs/RELEASING.md`, `modules/engine/README.md`, `NOTICE`, this repo's `README.md`.

## S8 — State synchronization (3.1, done; device for behaviour)

Root causes and design: [`docs/STATE_SYNC.md`](STATE_SYNC.md).

* Local assets revalidate (`ETag`/`no-cache`, 304) and carry a version query, so an
  update cannot be masked by a cached bundle.
* `SessionSync` reports background duration/engine liveness on resume;
  `web/src/patches/session_sync` nudges a live stream or performs one bounded
  reconciliation reload when the stream died, with a watchdog for mid-session
  drops and a reload budget that cannot loop against a dead engine.
* Regression tests: `web/tests/sync_test.js` (10 assertions, run by CI) covering
  resume, dead stream, self-reconnect, duplicate suppression, watchdog and budget.
* Engine audit: upstream still ships the identical engine archive
  (`sha256 a48ddf73…`, no newer commits), so Hydrogen/Carbon remain out of reach
  without a newer official engine; nothing is fabricated.

Requested matrix mapping: **E** and **F** are covered by the automated tests at
logic level; **A, B, C, D, G, H** below need the device.

## S9 — ACP backend (3.2.0-alpha.1, protocol verified)

Audit and decisions: [`docs/ACP_INTEGRATION_AUDIT.md`](ACP_INTEGRATION_AUDIT.md),
Hydrogen status: [`docs/HYDROGEN_COMPATIBILITY_REPORT.md`](HYDROGEN_COMPATIBILITY_REPORT.md).

* Verified Google publishes an official Bionic **android_arm64** CLI (1.5.9) with a
  SHA-512 manifest; `agy acp` is a real ACP v1 stdio server. Chosen runtime is a
  native Android process, not PRoot.
* Implemented JSON-RPC/ACP client, per-tool permission handling (deny by default),
  session create/load, dynamic model + thinking catalogue with honest statuses,
  verified CLI provisioning, reversible backend selection and a diagnostics panel.
* 24 protocol tests pass against a mock agent (`tools/acp_test/run.sh`, wired into CI).
* Not yet: ACP conversation renderer, in-app sign-in, any on-device run, any
  Hydrogen inference. Those are alpha.2 → beta.2 per the milestone plan.

## S7 — Device testing (device / not run)

No Galaxy A54, emulator, Google account or network is available in this
environment, so these are **not executed** and must be run on hardware:

| Check | How |
| :--- | :--- |
| Fresh install | `adb install -r dist/continuum.apk`, first-launch splash → UI |
| Login/authentication | Sign in with a real Google account; verify token refresh after restart |
| Existing workspaces | Open a workspace created before an update, check files are intact |
| Project file access | Read/write a project file through the agent and the file picker |
| Terminal/PTY | Open the terminal, run `pwd`, `ls`, an interactive command, resize |
| Agent execution | Run a small multi-file task to completion |
| Model selection | Open the model menu; confirm enabled models run and disabled rows show `account`/`engine` |
| Long-running task | Start a task, background the app, return after several minutes |
| Background operation | Lock the screen during a task; confirm it continues |
| Update installation | Publish a higher `versionCode`, then Check → Download → Install via the system installer |
| Failed-update recovery | Corrupt the download or interrupt it; expect a checksum error and no install |
| Android 16 compatibility | Target device: Samsung Galaxy A54 on Android 16 (One UI) |
