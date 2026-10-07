# bus

Modular shell bus for AGY. It composes the runtime environment and CLI args
for the patched `language_server` (or any target) and manages long-lived
services. No Android, no root, no proot required.

```
bus/bus.sh        thin entry: source lib/*.sh then cmd/*.sh, dispatch "$@"
bus/lib/          helpers (paths, log, services, endpoints, profile, channels)
bus/cmd/          one command handler per file (list, endpoints, svc, env,
                  doctor, run, selftest)
bus/modules/      drop-in channels, loaded lexical: modules/*.sh then elements/*.sh
bus/profiles/     env + TARGET + MODULES + EXTRA_ARGS presets
bus/selftest      offline acceptance test (bash, no network)
```

`elements/` and `run/` are runtime/optional directories, created on demand and
not tracked.

## Usage

```sh
bus/bus.sh list
bus/bus.sh run --profile core
bus/bus.sh run --profile core -- /path/to/language_server -flag
bus/bus.sh svc up core          # background core + endpoint registration
bus/bus.sh svc status core
bus/bus.sh svc down core
bus/bus.sh env   --profile core # print composed environment
bus/bus.sh doctor --profile core
bus/bus.sh endpoints
bus/bus.sh selftest
```

## Channels

A module `modules/NN-name.sh` declares the channel `name` (basename minus the
`NN-` prefix). Functions are optional except `name_up`:

| function | purpose |
|---|---|
| `name_up` | export env / edit PATH / set `BUS_CMD` (e.g. the glibc loader) |
| `name_down` | teardown for the launcher process |
| `name_args` | print extra target CLI args, one per line |
| `name_svc_up` / `name_svc_down` / `name_svc_status` | long-lived service |
| `name_doctor` | diagnostics for `bus doctor` |

## Modules

| module | channel | role |
|---|---|---|
| `00-prefix.sh` | `prefix` | PREFIX/HOME/TMPDIR/PATH/LD_LIBRARY_PATH/LD_PRELOAD; sources `$PREFIX/etc/profile.d/00-env.sh` |
| `10-glibc.sh` | `glibc` | glibc loader (`USE_LOADER=1`) + `LD_LIBRARY_PATH` |
| `20-certs.sh` | `certs` | `SSL_CERT_FILE` / `CURL_CA_BUNDLE` / `GIT_SSL_CAINFO` |
| `30-resolv.sh` | `resolv` | seeds `$GLIBC_PREFIX/etc/{resolv.conf,nsswitch.conf,hosts}`; `DNS_MODE=go|cgo` |
| `40-net.sh` | `net` | proxy passthrough, ALPN, GOTRACEBACK |
| `50-core.sh` | `core` | composes core args and runs it as a service |
| `60-pkg.sh` | `pkg` | stub: sets `PKG_ROOT`, advertises `pkg` endpoint |
| `70-files.sh` | `files` | Shizuku/`rish` toggle for shared storage |

## Helpers (available to every module)

`_log`, `_warn`, `_godebug`, `_spawn`, `_svc_pid`, `_svc_alive`, `_svc_stop`,
`_endpoint`, `_compose`, `_collect_args`.

## Endpoints

`_endpoint NAME VALUE` writes/replaces `NAME=VALUE` in `$BUS_RUN_DIR/endpoints`
(default `bus/run/endpoints`). `bus endpoints` prints the registry. Core
publishes `core_http`, `core_bin`; `pkg` publishes `pkg`; `files` publishes
`files_root` and, when Shizuku is on, `files_rish`.

## Ports

`CORE_PORT` defaults to `APP_PORT`, read from `config/app.env` (`PORT=45157`)
by `lib/paths.sh`; falling back to `45157` when the config is absent.

## Profiles

| profile | target | modules |
|---|---|---|
| `default` | (none) | prefix glibc certs resolv net |
| `core` | `../core/language_server` (loader on, cgo DNS) | prefix glibc certs resolv net core |
| `shell` | `$PREFIX/bin/bash --login` | prefix glibc certs resolv net |
| `tlx` | `$PREFIX/bin/tlx` (stub) | prefix glibc certs resolv net pkg files |

## Selftest

`bus/selftest` is offline and must exit 0: `bash -n` on every script,
`bus list`, `bus env --profile core`, `bus doctor`, an args-channel round trip
through a temp element, the endpoint registry, and a `svc up/down` cycle for a
trivial sleeper. No core, no network, no Android needed.
