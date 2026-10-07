# Architecture Specification: Antigravity for Android (AGY v2)

This document provides a comprehensive technical breakdown of the 5 architectural layers powering **Antigravity for Android**.

```
+--------------------------------------------------------------------------+
|                        Layer 1: Android Host App                         |
|   (Java 8, targetSdk 28, W^X bypass, Splash, Lifecycle, Icon Switcher)   |
+--------------------------------------------------------------------------+
                                    |
+--------------------------------------------------------------------------+
|                        Layer 2: Web UI & Bridges                         |
|  (React SPA, LocalAssetServer, AndroidBridge, Touch Menu, CSRF / Fetch)  |
+--------------------------------------------------------------------------+
                                    |
+--------------------------------------------------------------------------+
|                     Layer 3: Patched Go Core Engine                      |
| (language_server ELF64, glibc loader, pclntab parser, seccomp bypass)    |
+--------------------------------------------------------------------------+
                                    |
+--------------------------------------------------------------------------+
|                          Layer 4: Shell Bus                              |
|   (Modular compositor, profiles: core/shell, endpoints registry, daemons) |
+--------------------------------------------------------------------------+
                                    |
+--------------------------------------------------------------------------+
|                  Layer 5: Bionic Userspace & Toolchain                   |
|   (Termux prefix, tlx/pkg manager, agy-fix-paths, Shizuku rish bridge)    |
+--------------------------------------------------------------------------+
```

---

## 1. Layer 1: Android Host App (`app/`)

### Platform Constraints & Core Choices
- **`targetSdkVersion 28` (Android 9 Pie):** Critical security bypass. Starting in Android 10 (API 29), Android enforces W^X (Write XOR Execute) memory protection on application private storage (`filesDir`), preventing direct execution (`execve`) of binaries. Targeting API 28 retains backward compatibility, enabling glibc binaries (`libloader.so`, `liblanguage_server.so`) to execute directly out of the app's private directory.
- **`minSdkVersion 24` (Android 7.0 Nougat):** Broad compatibility across modern and legacy devices.
- **Zero-Framework Architecture:** Implemented in pure Java 8 using the standard Android SDK without Gradle, Jetpack Compose, or heavyweight runtime dependencies.

### Key Components
1. **`MainActivity`:** Root single-task activity hosting the primary WebView, lifecycle bindings, and background supervisor.
2. **Dynamic Themed Launchers (`LauncherIconSwitcher`):**
   - Declares two concrete launch activities: `LauncherDarkActivity` (enabled by default) and `LauncherLightActivity`.
   - Switches them at runtime via `PackageManager.setComponentEnabledSetting` with `DONT_KILL_APP`.
   - Avoids `activity-alias` limitations, ensuring the Android OS Starting Window reads the correct splash theme attributes without day/night mode conflicts.
3. **3-Stage Splash Overlay (`SplashController`):**
   - Hosts a transparent WebView overlay above the main canvas.
   - Operates in three states: `MODE_LOADING` (pulsing animation), `MODE_TRANSIENT` (countdown / recovery status), and `MODE_ERROR` (crash screen with an interactive Retry button).
   - Dismissal occurs strictly on **first UI render** via `AndroidBridge.onUiReady()`, eliminating blank page flicker.
4. **Process Supervisor (`CoreProcessSupervisor` & `CoreRestartPolicy`):**
   - Executes the engine via `CoreLauncher` as a Foreground Service (`CoreServerService`) with an ongoing notification and `WakeLock`.
   - Reads merged process stdout/stderr into `files/logs/core.log`.
   - Probes the loopback HTTPS port (`CoreReadyProbe`). On crashes, it triggers an exponential backoff restart policy (2s, 5s, 10s); after 60s of sustained uptime, the failure counter resets.

---

## 2. Layer 2: Web Interface & Android Bridge (`web/`)

### High-Performance Asset Serving (`LocalAssetServer`)
To prevent the Go core from wasting CPU cycles serving static files:
- `AgyWebViewClient.shouldInterceptRequest()` intercepts incoming requests to `https://127.0.0.1:<PORT>/`.
- Static assets (JS, CSS, HTML, PNG, fonts) are served directly from flash storage (`filesDir/web/`) with `Cache-Control: immutable`.
- ConnectRPC / gRPC-Web and streaming endpoints (`/exa.*`, `/jetski.*`, `Service`, `connect-websocket`) pass through unimpeded to the loopback backend socket.

### JavaScript $\leftrightarrow$ Android Bridge (`AndroidBridge`)
Exposed via `addJavascriptInterface(bridge, "Android")` (and aliased to `window.AndroidBridge`):
- `onUiReady()`: Signals successful React DOM rendering; triggers smooth splash fade-out.
- `onUiError(err)`: Traps uncaught syntax and promise errors, showing the native error recovery dialog.
- `setTheme(mode)` / `getTheme()`: Synchronizes theme changes to Android status/navigation bars and launcher icons.
- `getConfig()`: Returns serialized `window.__APP_CONFIG__` parameters (paths, ports, version).
- `openUrl(url)`: Handles external links and OAuth login flows via Chrome Custom Tabs.
- `exec(cmd)` / `shizuku(cmd)`: Dispatches elevated shell commands through Shizuku (`rish`).

### Touch & Mobile Optimizations
- **300ms Tap Delay Elimination:** Injects `touch-action: manipulation` across all clickable elements.
- **Virtual Viewport Adaptation:** Tracks `window.visualViewport.resize` and updates `--agy-vvh` for accurate layout sizing when the on-screen keyboard toggles.
- **Touch Model Selector Patch:** Intercepts hover-dependent Radix/Base UI dropdowns, synthesizing pointer events and `ArrowRight` keystrokes on tap.

---

## 3. Layer 3: Patched Go Core Engine (`patches/`)

The core binary (`language_server`, ~174 MB) is a 64-bit ELF executable compiled for Linux AArch64. Because Android kernels and environments diverge from standard GNU/Linux distributions, it undergoes automated Ahead-of-Time patching:

```
[Vanilla Go Core ELF]
        │
        ├──> groups/syscalls: seccomp bypass (faccessat2 / fchmodat2 -> faccessat / fchmodat)
        ├──> groups/gates: eligibility & CLI authorization override
        ├──> groups/resolv: length-preserving DNS path rewrite (/etc/resolv.conf -> etc//resolv.conf)
        └──> groups/auth: OAuth redirect deep link patch
        │
[Patched Core Engine] ── verified by runtime.manifest.json (SHA-256)
```

For complete bytecode signatures and pclntab parsing details, see [`docs/PATCHES.md`](PATCHES.md).

---

## 4. Layer 4: Shell Bus (`bus/`)

The Shell Bus is an independent, modular environment compositor and process supervisor written in pure POSIX Bash.

### Channel Architecture
Each module in `bus/modules/` implements standard lifecycle functions:
- `<channel>_up`: Sets environment variables and configures the loader.
- `<channel>_down`: Cleans up state in reverse activation order (LIFO).
- `<channel>_args`: Emits CLI parameters for the binary.
- `<channel>_svc_up|down|status`: Manages daemon execution (`_spawn` with PID tracking).
- `<channel>_doctor`: Diagnostic health checks.

### Standard Pipeline
1. `00-prefix`: Configures `PREFIX`, `HOME`, and loads `etc/profile.d/00-env.sh`.
2. `10-glibc`: Mounts glibc libraries and sets the dynamic linker (`ld-linux-aarch64.so.1`).
3. `20-certs`: Discovers CA certificate bundles and exports `SSL_CERT_FILE` & `CURL_CA_BUNDLE`.
4. `30-resolv`: Injects Android loopback DNS seeds into glibc's resolver.
5. `40-net`: Enforces gRPC ALPN and silences unnecessary Go panic noise.
6. `50-core`: Assembles parameters and launches `language_server`.
7. `70-files`: Exposes `/storage/emulated/0` and activates Shizuku (`rish`).

---

## 5. Layer 5: Bionic Userspace & Toolchain (`env/`)

To provide a full developer environment without containers, AGY incorporates an optimized Android Bionic userspace:
- **`pkg` Package Manager (`tlx`):** A custom package manager written in pure Bash & AWK. Resolves Debian package dependencies, verifies SHA-256 sums, parses `.deb` archives directly via `ar`/`tar`, and manages installations without requiring `apt` or `dpkg` binaries.
- **Length-Preserving Binary Rewriting:** Replaces hardcoded `/data/data/com.termux/files/usr` paths with `/data/data/com.agy///files/usr` without shifting ELF symbol tables.
- **`agy-fix-paths` Utility:** Rewrites script shebangs and configuration paths upon package extraction.
- **Standalone Shizuku (`rish`) Bridge:** Connects to Shizuku via `app_process` without linking external Java SDKs, with built-in `chmod 400` protections for Android 14+ (API 34).
