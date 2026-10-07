# Shell Bus (`bus/`) & Bionic Userspace (`env/`)

This document details the Shell Bus modular architecture and the lightweight Bionic Linux userspace (`tlx`) bundled with **Antigravity for Android**.

---

## 1. Modular Shell Bus (`bus/`)

The Shell Bus orchestrates process execution, environment variables, glibc loading, and background daemon services without requiring root or containers.

### Channel Contract
A module `modules/NN-<name>.sh` exports functions for channel `<name>`:

| Function | Purpose |
|---|---|
| `<name>_up` | Exports variables, edits `PATH`, sets dynamic loader (`BUS_CMD`). *(Required)* |
| `<name>_down` | Teardown logic upon process termination. Called in reverse order (LIFO). |
| `<name>_args` | Emits CLI arguments for the target binary (one per line). |
| `<name>_svc_up` | Starts a long-lived service in the background (`_spawn`). |
| `<name>_svc_down` | Stops the running service via its PID file. |
| `<name>_svc_status` | Probes service health. |
| `<name>_doctor` | Outputs diagnostic checks for `bus doctor`. |

### Available Modules
- **`00-prefix.sh` (`prefix`):** Establishes `$PREFIX` (`files/usr`), `$HOME`, `$TMPDIR`, and sources `etc/profile.d/00-env.sh`.
- **`10-glibc.sh` (`glibc`):** Mounts the glibc library tree and invokes the dynamic linker (`ld-linux-aarch64.so.1`).
- **`20-certs.sh` (`certs`):** Locates TLS CA certificate bundles and exports `SSL_CERT_FILE` & `CURL_CA_BUNDLE`.
- **`30-resolv.sh` (`resolv`):** Seeds glibc resolver configuration (`resolv.conf`, `nsswitch.conf`, `hosts`) and configures `GODEBUG=netdns=cgo`.
- **`40-net.sh` (`net`):** Manages proxy variables and sets `GRPC_ENFORCE_ALPN_ENABLED`.
- **`50-core.sh` (`core`):** Assembles CLI flags (`-http_server_port`, `-web_bundle_path`, `-csrf_token`) and daemonizes `language_server`.
- **`60-pkg.sh` (`pkg`):** Registers package manager endpoints.
- **`70-files.sh` (`files`):** Exposes shared storage (`/storage/emulated/0`) and initializes Shizuku (`rish`).

---

## 2. Bionic Userspace & `pkg` Package Manager (`env/`)

AGY incorporates a Termux-compatible Bionic userspace without shipping the heavy Termux application.

### `pkg` Architecture (`tlx`)
Written entirely in POSIX Bash & AWK:
- **`deb.sh`:** Directly reads Debian `.deb` archives (format `ar`), unpacks `control.tar.*` and `data.tar.*` using `dd`/`tar`, and strips path prefixes (`--strip-components=6`).
- **`db.sh`:** Tracks installed files in `$PKG_ROOT/installed/<pkg>.meta` and `<pkg>.files`. Synchronizes with Debian `/var/lib/dpkg/status`.
- **`vercmp.awk`:** Full AWK implementation of Debian version comparison algorithm (`dpkg --compare-versions`).
- **`resolver.sh`:** Topological dependency sorting supporting alternatives (`|`) and version comparisons (`>=`, `<=`, `=`).

### Binary & Script Path Rewriting
To allow precompiled Termux `.deb` packages to operate in `com.agy`:
1. **ELF Patching:** Replaces `/data/data/com.termux/files/usr` with `/data/data/com.agy///files/usr` in `dpkg` binaries.
2. **`agy-fix-paths`:** Scans newly installed scripts and adjusts shebang lines (`#!/data/data/com.agy/files/usr/bin/...`).

---

## 3. Shizuku (`rish`) Integration

The `rish` bridge connects directly to the running Shizuku service via Android's internal `app_process`:
- **Android 14+ Security (API 34):** Enforces `chmod 400` on dynamic dex files (`rish_shizuku.dex`) to prevent execution blocks.
- **Clean Execution:** Runs with `env -u LD_PRELOAD -u LD_LIBRARY_PATH` to isolate the host Android framework from glibc/Termux libraries.
