<div align="center">

# Continuum

**An independent, maintained Android fork of [Antigravity for Android](https://github.com/werdio325-png/antigravity-android).**

Runs Google's Antigravity engine natively on Android/ARM64 — no PRoot, no VM — and
keeps the app honest about which Gemini models the embedded engine can actually
reach.

</div>

---

## What this fork is

Continuum is a fork, not a rebrand of a download. It keeps the whole upstream
runtime (native Bionic engine, glibc loader, shims, WebView UI, OAuth, terminal,
workspaces) and changes only the things a fork must own:

| Area | Upstream | Continuum |
| :--- | :--- | :--- |
| App name | Antigravity for Android | **Continuum** (`Continuum Dev` for dev builds) |
| Application id | `com.agy` | `dev.continuum.antigravity` (`.dev` for dev builds) |
| Engine port | 45157 | **45167 / 45168** so both apps can run side by side |
| Gateway port | 45160 | **45170 / 45171** |
| Signing | shared debug key | **separate release key**, fail-closed in CI |
| Region bypass edition | shipped | **removed** — `--region-bypass` exits with an error |
| Model availability | fixed, unexplained | **read from the embedded engine**, explained per row |
| Updates | manual APK download | **in-app updater** from this repo's GitHub Releases |
| Upstream tracking | manual | scheduled workflow that opens reviewable PRs |

Installing Continuum does not touch an existing Antigravity installation: different
package id, different ports, different signing key, separate data directory.

## Model availability — what actually happens

Continuum never invents a model and never pretends one is available.

`modules/engine` reads the model catalogue **out of the embedded engine binary**
at build time and ships it as `assets/runtime/engine_info.json`. The UI then:

* lists what the engine reports (no hardcoded model table),
* marks a row **“engine”** when this engine build has no such model id at all,
* marks a row **“account”** when the engine knows the model but the menu is
  disabled — that decision belongs to Google's servers and the signed-in account,
* leaves enabled rows untouched.

So a model absent from the engine (as `gemini-4-hydrogen` is in the currently
embedded 2.11.0 build) is reported as needing a newer engine, while a model the
engine knows but the account cannot use is reported as an account matter. Neither
case is faked, and no eligibility, entitlement or regional check is bypassed.

Details and evidence: [docs/AUDIT.md](docs/AUDIT.md).

## In-app updates

The **Updates** button opens a panel with the installed version, the latest release
from this repository, its changelog and download size. Downloading verifies the
release's published SHA-256 before anything is offered for installation, and
installation goes through Android's own package installer — the user can cancel,
and updates can be postponed. Nothing installs silently, and user data (workspaces,
projects, tokens) is preserved because the package id and signing key stay stable.

## Build

```bash
bash tools/gen_keys.sh   # once: creates the fork release key (git-ignored)
bash build.sh            # -> dist/continuum.apk
bash tools/verify_apk.sh dist/continuum.apk
```

Requirements, toolchain layout and dev builds: [docs/BUILDING.md](docs/BUILDING.md).
Release process and CI secrets: [docs/RELEASING.md](docs/RELEASING.md).

## Status

The engine/patch/runtime/build stages are verified in this repository (tests +
signed APK build). Device behaviour on the target Galaxy A54 (Android 16) — login,
PTY, long agent runs, installer flow — has **not** been executed here, because no
device or emulator is available in the build environment. See
[docs/IMPLEMENTATION_PLAN.md](docs/IMPLEMENTATION_PLAN.md) for the exact test matrix
and what remains.

## License and attribution

Continuum is distributed under the **Apache License 2.0**, the same license as the
upstream project. Upstream copyright and attribution are retained; see
[NOTICE](NOTICE) and [LICENSE](LICENSE). The embedded `language_server` binary and
the Antigravity web UI remain Google's; this fork only patches and packages them
as the upstream project already does.
