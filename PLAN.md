# AGY v2 — plan & status

Rebuild of `../antigravity` into a many-small-files, AI-friendly tree.
Behavior ported 1:1; only structure changes, plus dead-code removal and bug fixes.

## Locked decisions

- v2 root: `antigravity v2/`, alongside the original (original untouched).
- `env/` never modified — referenced via `config/paths.env`.
- Heavy inputs (core, glibc, certs, seed, tools, env) referenced by absolute path
  (symlinks unavailable on the FUSE mount). Light web upstream copied into `web/`.
- Web custom JS split into `web/src/**`, concatenated by `web/build.sh` into
  `web/generated/**` (many sources, few runtime bundles).
- Constants live only in `config/app.env`; generated into `BuildConfig.java` +
  `values/gen.xml`.
- Java package root stays `com.agy.*`; dev edition only changes the manifest
  `package` attribute and label/port.

## Interfaces frozen

See `SPEC.md`. `patches` keeps `run(target_path, verify, dry_run, allow_missing, log)`.
`web` keeps `window.__APP_CONFIG__`, `window.process`, `csrfToken` cookie,
`window.fetch`, `window.Android`/`AndroidBridge`.

## Status

| layer | state | evidence |
|---|---|---|
| config | done | `config/*.env` |
| build | done | `bash build.sh` and `--dev` both BUILD OK |
| patches | done | apply+`--verify` exit 0; sha `cc380c5d…` |
| web | done | 45 fragments -> 8 bundles; `node --check` ok |
| bus | done | `selftest` 12/12 |
| app (Java) | done | 109 files; javac 0 errors |
| APK equivalence | done | patched core sha == original APK core |

## Bugs fixed vs original

- `bridge.core.js` keepalive referenced undefined `noop` (now removed).
- `index.html` always forced `dark`, ignoring `light`.
- Port drift (app 45157 / bus 8080) — single source now.
- Duplicate night resources removed; dropped dead API surface.

## Follow-ups (not in v2)

- Missing `system.png` referenced by `main.js` (upstream asset) — decide add/drop.
- On-device smoke test (launch, OAuth, theme, model menu) — requires a device.
