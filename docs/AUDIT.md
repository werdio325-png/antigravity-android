# Repository audit — Antigravity for Android

Audit performed on `werdio325-png/antigravity-android` before any fork changes.
Everything below is from the checked-out tree and the embedded binaries, not from
assumptions about the desktop product.

## 1. What the project actually is

A self-contained Android host that runs Google's Antigravity **engine**
(`language_server`, a Go binary built for Android/Bionic) inside the app's data
directory, drives it over localhost HTTP, and renders the Antigravity web UI in a
WebView. There is no Gradle build and no Android SDK project in the usual sense:
the APK is assembled by a Python/shell pipeline.

| Layer | Location | Notes |
| :--- | :--- | :--- |
| Host app (Java) | `app/src/main/java/` | 125 files, 196 classes; activity, service, WebView host, bridges, OAuth, runtime supervisor. |
| Engine (Go) | `modules/core/pristine/language_server.tar.gz` | `language_server` 2.11.0, build tag `go1.28-20260921-RC02`, 181,358,800 bytes, ELF64 AArch64 PIE. sha256 `a48ddf73…` of the archive. |
| Binary patches | `modules/patches/` | Length-preserving byte patches applied to the engine (bionic exec paths, bash→sh for PTY, LSE/SIMD, region eligibility for the bypass edition). |
| Runtime assembly | `modules/core/bundle`, `modules/runner` | Extracts, patches, lays out `runtime/{bin,lib,certs,seed}`, writes `manifest.json` with per-file SHA-256. |
| Native userspace | `modules/core/glibc`, `modules/core/shims` | glibc loader + libraries and shims so the Bionic-targeted binary and its helpers run consistently. |
| Web frontend | `web/` | React bundle `web/main.js` (WebView asset) plus a patch system (`web/src`, `web/src.manifest`, `web/build.sh`) that injects fragments into the bundle and `index.html`. |
| Build | `modules/builder/*.sh`, `build.sh` | `aapt` → `javac` → `d8` → asset staging → zip → `zipalign` → `apksigner`; plus an env bootstrap overlay. |
| CI | `.github/workflows/build-and-release.yml` | Built two editions (standard + region-bypass) with a debug keystore. |

### Data flow

1. `MainActivity` starts `RuntimeService`, which extracts `assets/runtime` to
   `filesDir/runtime`, verifies every file against `manifest.json`, then starts
   the engine under the glibc loader on port `45157`.
2. The WebView loads `assets/web/index.html` (based at the engine's origin), with
   `__APP_CONFIG__` injected (version, CSRF token, ports, desktop mode).
3. JS talks to the engine over HTTP and to native code through
   `addJavascriptInterface(..., "Android")` (privileged mutations gated by a
   per-process native token).
4. OAuth goes through a custom tab / browser intent; tokens live in the app's
   private storage. Nothing is shared with the original app's package.

## 2. Where the engine comes from and how it updates

The engine is **vendored**: a tarball committed to `modules/core/pristine/`. It is
not downloaded at runtime, and there is no mechanism to update it independently of
an APK release. `prepare_runtime.py` pins the SHA-256 of the *patched* output
(`1d9750dd…` standard, `401c7394…` no-bypass), so any change to the engine or the
patch set fails the build until the pins are updated deliberately.

Consequence: adopting a newer official engine is a build-time operation, which is
exactly what the new `modules/engine` layer makes reviewable.

## 3. Why "Gemini 4 Hydrogen / Argon / Carbon" look unavailable

Three separate causes, established from evidence:

1. **Engine age.** The embedded engine (2.11.0, Go toolchain tag dated 2026-09-21)
   contains `gemini-4-argon` and its `-low/-medium/-high` tiers, plus a large
   Gemini 3.x family. It contains **no** `gemini-4-hydrogen` id in any form and no
   model-shaped `Carbon` identifier. Those models therefore cannot be offered by
   this binary regardless of the account: the string the client would have to send
   does not exist in it.
2. **Server-side gating for what does exist.** The engine queries
   `entitlement.userTier`, `fetchAdminControls` and `fetchQuotaStatus`, and the
   model menu's disabled state comes from those responses. Argon being present but
   disabled is an account/rollout decision made by Google, not an app bug.
3. **No client catalogue to patch.** `web/main.js` contains no text model table
   (only image-model entries), so the fork has nothing local that could be edited
   to “enable” a model. Any attempt to force one would be a lie at best and a
   policy violation at worst.

What the fork does about it: it reads the engine's real catalogue at build time,
shows it, and explains unavailability honestly (`engine` vs `account`), and it
ships a reviewed upgrade path so a newer engine can be adopted without guessing.

## 4. Authentication and eligibility

OAuth is the user's own Google sign-in; tokens stay in app-private storage. The
upstream "region bypass" edition patches engine bytes so the eligibility check
fails open. The fork **removes** that edition entirely: `--region-bypass` exits
with an error, only the `--no-bypass` patch set is staged, and the release
pipeline builds a single standard APK. No entitlement, regional restriction or
account check is bypassed anywhere in the fork.

## 5. Limitations and dependencies found

| Item | Impact |
| :--- | :--- |
| Engine is a single vendored 181 MB tarball | Engine upgrades are APK releases; no OTA engine channel exists (and none is added, to avoid executing unverified binaries). |
| `targetSdk 28` | Required so the app can execute a binary from its data directory. Cannot be raised without redesigning execution; it is intentional, not an oversight. |
| Version pins in `prepare_runtime.py` | Every engine/patch change must update two hashes; the build fails closed otherwise. |
| No Gradle | Tooling must exist on the build host (`aapt`, `zipalign`, `apksigner`, JDK 17, Python 3). CI installs them. |
| WebView UI is a minified vendor bundle | Behaviour is extended through `web/src` patches; `web/main.js` itself is generated/patched, and rebuilds are verified by `web/tests/selftest.sh`. |
| Upstream publishes no stable engine/API contract | The compatibility layer reports drift (patch signatures, catalogue) instead of assuming it. |
| No device in the build environment | Phase 7 device behaviour (login, PTY, long tasks, installer) must be validated on the Galaxy A54; see `docs/IMPLEMENTATION_PLAN.md`. |

## 6. Existing functionality the fork preserves

Native runtime extraction + manifest verification, engine supervision and restart,
OAuth login and token refresh, workspace/project file access, terminal/PTY, file
picker, downloads, log export, torch bridge, background daemon, desktop-mode
toggle, theme sync, and the LSE/SIMD compatibility shims. None of these were
rewritten; they were kept and re-pointed at the new package id and ports.
