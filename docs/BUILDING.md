# Building Continuum

The project does not use Gradle. `build.sh` drives a Python/shell pipeline that
assembles the APK directly.

## Requirements

| Tool | Version | Notes |
| :--- | :--- | :--- |
| Python | 3.9+ | build scripts and engine probe |
| JDK | 17 | `javac` for 196 classes, `keytool` |
| Android build-tools | 34 | `aapt`, `zipalign`, `apksigner` on `PATH` |
| `zip`, `unzip`, `sha256sum`, `base64` | any | staging and checksums |
| `tools/android.jar` | API 28 | committed |
| `tools/r8.jar` | 8.2.x | committed; provides `d8` |

Linux x86_64 or WSL. Roughly 3 GB free disk (the engine tarball expands to ~181 MB
plus staging).

## First build

```bash
bash tools/gen_keys.sh        # creates keys/continuum-release.jks + keys/keystore.properties
bash build.sh                 # -> dist/continuum.apk
bash tools/verify_apk.sh dist/continuum.apk
```

Both key files are git-ignored. Keep them: Android only upgrades an app when the
signing certificate matches, so losing this key means every user must uninstall
(and lose local data) to take a future build.

`build.sh` stages only the standard (no-bypass) patch set. `--region-bypass` is
rejected by design.

## Dev build (debuggable, separate identity)

```bash
DEV_MODE=1 bash build.sh
```

This produces `dev.continuum.antigravity.dev` with its own ports, uses the debug
keystore when no release key exists, and can be installed next to a release build.

## What happens during a build

1. `01_gen_config.py` — renders `BuildConfig.java`, `gen.xml`, the merged manifest
   from `config/app.env`.
2. `02_r_java.sh` — generates `R.java` for the fork package.
3. `03_compile_java.sh` — compiles `app/src/main/java`.
4. `04_dex.sh` — `d8` → `classes.dex`.
5. `prepare_runtime.py` — extracts the pristine engine, applies the patch set,
   verifies the patched SHA-256 pins, assembles `runtime/`, probes the engine and
   writes `engine_info.json`, then rebuilds `manifest.json`.
6. Web assets: `web/build.sh` bundles `web/src` fragments into
   `web/generated/` and `index.html` references them.
7. Env overlay + asset staging, `aapt package`, `zipalign`, `apksigner`.
8. `09_verify.sh` verifies the signature.

## Changing the engine

Engine updates are deliberate build-time work, never a download:

```bash
python3 modules/engine/upgrade_engine.py --archive /path/to/language_server.tar.gz
python3 modules/engine/upgrade_engine.py --archive /path/to/language_server.tar.gz --apply
```

Review the catalogue diff and patch report first. Then rebuild; the SHA-256 pins in
`prepare_runtime.py` must be updated as `--apply` does, and any patch whose
signature no longer matches must be re-derived before the build will pass.

## Tests

```bash
bash test_all_modules.sh                                  # everything incl. E2E server test
python3 -m unittest discover -s modules/engine/tests -p 'test_*.py' -v
bash web/tests/selftest.sh                                # bundles + JS syntax
```

The engine probe's end-to-end case needs the real 181 MB archive and is opt-in
(`AGY_ENGINE_E2E=1`), so the default suite stays fast.
