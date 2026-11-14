# Releasing Continuum

The in-app updater reads **this repository's** latest GitHub Release, so the release
format is part of the app's contract.

## Contract the app depends on

| Item | Value |
| :--- | :--- |
| Release | the repository's latest non-draft release |
| Tag | `v<version>`, matching `APP_VERSION_NAME` in `config/app.env` |
| APK asset | `continuum.apk` |
| Checksums asset | `SHA256SUMS.txt` (`sha256sum` format, one line per asset) |
| Notes | `RELEASE_NOTES.md` is used as the release body and shown in the app |

`BuildConfig.UPDATE_REPO` / `UPDATE_ASSET` / `UPDATE_CHECKSUMS_ASSET` come from
`config/app.env`. If the asset name changes, change `config/app.env` too —
otherwise the app finds the release but no installable file.

## One-time CI setup

Add these repository secrets (Settings → Secrets and variables → Actions):

| Secret | Content |
| :--- | :--- |
| `CONTINUUM_KEYSTORE_BASE64` | `base64 -w0 keys/continuum-release.jks` |
| `CONTINUUM_KEYSTORE_PASSWORD` | keystore password |
| `CONTINUUM_KEY_ALIAS` | key alias (default `continuum`) |
| `CONTINUUM_KEY_PASSWORD` | key password |

Never commit these values. Release builds **fail** when the keystore secret is
missing, so a release can never silently fall back to a debug key.

## Release steps

1. Bump `APP_VERSION_NAME` and `APP_VERSION_CODE` in `config/app.env`
   (numeric, monotonically increasing — Android refuses to downgrade).
2. Update `RELEASE_NOTES.md` with what changed.
3. Run the suites locally: `bash test_all_modules.sh` and `bash web/tests/selftest.sh`.
4. `bash build.sh` and `bash tools/verify_apk.sh dist/continuum.apk`.
5. Commit, tag, push:

   ```bash
   git commit -am "Release vX.Y.Z"
   git tag vX.Y.Z
   git push origin main --follow-tags
   ```

6. The `Build Continuum APK` workflow runs tests, builds, verifies the package id,
   version, non-debuggable flag and the AArch64 engine, checks tag vs app version,
   writes `SHA256SUMS.txt` and publishes the release.

Draft releases made with `workflow_dispatch` are not offered by the updater until
they are published as the latest release.

## Why upgrades keep user data

Same package id + same signing certificate ⇒ Android performs an in-place update.
Workspaces, projects, tokens and settings live in the app's data directory and are
untouched by the package installer. Installing a build signed with a *different*
key fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; that is the signal that the
release key in CI does not match the published key, not a reason to uninstall.

## Upstream changes

`Upstream sync (review only)` runs daily. A clean merge becomes a **draft PR** after
the test suites and fork-identity assertions pass; a conflicted merge opens an issue
instead. Nothing is merged or released automatically — review, test on the A54, then
merge and release manually.
