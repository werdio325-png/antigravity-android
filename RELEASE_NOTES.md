## Continuum 3.0.0 — first independent fork release

Continuum is a maintained fork of Antigravity for Android. It keeps the complete
upstream runtime and adds the maintenance machinery a fork needs.

### Identity
- New application id `dev.continuum.antigravity` and name **Continuum**, so this
  installs alongside the original app without touching it or its data.
- Separate engine/gateway ports (45167/45168, 45170/45171) so both apps can run at
  the same time.
- Separate release signing key; release builds fail closed without it.
- The region-bypass edition is removed. `--region-bypass` now exits with an error.

### Engine compatibility
- New `modules/engine` layer reads the real engine version, build tag and model
  catalogue from the embedded binary at build time (`assets/runtime/engine_info.json`).
- The model menu annotates rows instead of guessing: **engine** means this engine
  build has no such model, **account** means Google is not offering it to the
  signed-in account. Nothing is enabled locally and no eligibility check is bypassed.
- `upgrade_engine.py` validates a new official engine archive, diffs its catalogue
  and reports patch compatibility before it is adopted.

### Updates
- In-app updater: Check for updates, installed vs latest version, changelog,
  download size, progress, SHA-256 verification against the release's published
  checksums, Android's own package installer, and Later to postpone.
- Downloaded files that fail checksum verification are deleted and never installed.

### Maintenance
- CI builds and verifies a signed ARM64 APK, publishes checksums and a versioned
  release the updater can read.
- Daily upstream-sync workflow opens a draft PR for review — never an automatic merge.

### Known limitations
- Embedded engine is `language_server` 2.11.0 (`go1.28-20260921-RC02`). It contains
  `gemini-4-argon` (plus low/medium/high) but no `gemini-4-hydrogen`; Hydrogen-class
  models require a newer engine build to be adopted through the upgrade tool.
- Device behaviour (login, PTY, long agent runs, in-app install) was not exercised
  in the build environment; see `docs/IMPLEMENTATION_PLAN.md` for the test matrix.
