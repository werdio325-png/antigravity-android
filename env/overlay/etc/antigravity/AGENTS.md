# Antigravity Android Agent Rules & Environment Guidelines

> [!NOTE]
> This rule is automatically loaded into every conversation in Antigravity for Android (AGY v2).
> It defines the operating environment, execution rules, package management, native MCP tools, bundled skills, elevated permissions, and development standards.

---

## 1. Platform & Environment Identity

- **Platform:** Native Android (Linux kernel 6.1+, ARM64 / `aarch64` architecture, **ARMv8.0-A+ baseline** with ARMv8.0 LSE emulation and native ARMv8.1-A+ LSE atomics).
- **Application Context:** Runs directly inside the Antigravity (`com.agy` / `com.agy.dev`) sandbox.
- **Key Filesystem Paths (always resolve dynamically via `$HOME` / `$PREFIX`):**
  - Application Root (`$HOME`): App-private `files/` directory (e.g. `/data/user/0/com.agy/files`).
  - Linux Userspace Prefix (`$PREFIX`): `$HOME/usr`.
  - Binary Directory: `$PREFIX/bin`.
  - Runtime Internals: `$HOME/runtime/bin`.
  - Core & Gateway State: `$HOME/logs/core.state.json` and `$HOME/logs/gateway.port`.
  - External / Shared Storage: `/storage/emulated/0` (Projects typically reside in `/storage/emulated/0/Documents/`).
  - AI State, Global MCP Config & Skills: `$HOME/.gemini/config/mcp_config.json`, `$HOME/.gemini/config/skills/`, `$HOME/.gemini/config/AGENTS.md`.
  - Conversation Brain & Artifacts: `$HOME/.gemini/antigravity-app/brain/<conversation-id>/`.

---

## 2. Terminal Execution Rules

- **Pre-configured Environment (NO REDUNDANT EXPORTS):**
  Antigravity pre-configures `$PATH` (`$PREFIX/bin:/system/bin`), `$HOME`, and `$PREFIX` for every shell command.
  **DO NOT** prepend `export PATH=...` or `export HOME=...` before commands. Execute binaries (`python`, `git`, `pkg`, `bash`, `agy-daemon`, `agy-mcp-android`) directly.
- **No Bubblewrap / Desktop Sandboxing:**
  Standard desktop Linux bubblewrap (`bwrap`) is **not supported** by the Android kernel and SELinux. Never attempt to invoke `bwrap` or run commands wrapped in desktop sandboxes. All shell commands execute directly within the native userspace shell.
- **Shared Storage `noexec` Restriction:**
  Android mounts `/storage/emulated/0` with the `noexec` flag. Directly running `./script.sh` or executable binaries from shared storage fails with `Permission denied` (exit code 126).
  **Always invoke scripts with their explicit interpreter:**
  ```bash
  bash script.sh
  python script.py
  sh script.sh
  ```
  Native compiled binaries must reside in `$PREFIX/bin` or internal storage (`$HOME/`).
- **Path Quoting:**
  Shared storage paths frequently contain spaces (e.g. `/storage/emulated/0/Documents/antigravity release/`). Always quote file paths in commands.
- **Git on Android Shared Storage:**
  Git in `/storage/emulated/0/...` may trigger `fatal: detected dubious ownership`. Always configure safe directory and avoid nonexistent Termux system config:
  ```bash
  export GIT_CONFIG_NOSYSTEM=1
  git config --global --add safe.directory "<path>"
  ```

---

## 3. Native Android MCP Servers (`android-device` & `chrome_devtools`) & Bundled Skills

### Pre-configured MCP Servers (`~/.gemini/config/mcp_config.json`)
Antigravity automatically seeds two zero-dependency stdio MCP servers into `$HOME/.gemini/config/mcp_config.json` (invoked via Bionic bridges in `$HOME/runtime/bin/`):

1. **`chrome_devtools` (`$HOME/runtime/bin/agy-mcp-browser` -> `$PREFIX/bin/agy-mcp-browser`)**:
   - Standard Chrome DevTools MCP tool surface backed by native Android WebView (`SystemGatewayServer` `browser_auto`, zero desktop Chrome required): `list_pages`, `new_page`, `select_page`, `close_page`, `navigate_page`, `take_snapshot` (assigns `uid=e1..eN` to interactive elements), `take_screenshot` (PNG path + inline image), `click`, `fill`, `press_key`, `evaluate_script`, `list_console_messages`, `wait_for`.
2. **`android-device` (`$HOME/runtime/bin/agy-mcp-android` -> `$PREFIX/bin/agy-mcp-android`)**:
   - Communicates with `SystemGatewayServer` (`127.0.0.1:<port>`, port in `$HOME/logs/gateway.port`, default `45160`), Shizuku (`rish`), and `agy-daemon`:

- **Device & Feedback:** `android_device_info` (battery, charging, network, memory, storage), `android_toast`, `android_vibrate`, `android_torch`.
- **Clipboard:** `android_clipboard_get`, `android_clipboard_set`.
- **Notifications, Interactive Approvals & Subagent Progress:** `android_notify` (supports progress bars, `agy_tasks`/`agy_alerts`/`agy_subagents` channels, and `approval: true` preset with **Approve**, **Deny**, and **Deny w/ Comment** inline `RemoteInput` reply), `android_wait_approval` (returns `decision` + inline `comment`), and `android_subagent_notify` (low-buzz progress notifications with one-tap **Cancel** wired to ConnectRPC `CancelCascadeInvocation`).
- **Voice Session Control:** `android_voice_service` (`start`, `stop`, `mute`, `unmute`, `toggle_mute`, `status` on `VoiceForegroundService` for audio focus & speakerphone routing).
- **Elevated ADB Shell:** `android_shizuku_exec` (runs `logcat`, `pm`, `am`, `dumpsys`, `screencap` via `rish`).
- **Mobile Browser Automation:** `android_browser_automate` (isolated offscreen WebView supporting `navigate`, `eval`, `click`, `type`, `get_text`, `screenshot`, `console_logs`, `close` — always call `close` when finished to release memory).
- **Autonomous Scheduling:** `android_schedule_task` (`add`, `list`, `pause`, `resume`, `remove`, `run_now`, `status` on `agy-daemon`).

### Bundled Skills
Three curated skills are automatically seeded into `$HOME/.gemini/config/skills/` (and mirrored in `$PREFIX/etc/antigravity/skills/`):
- **`android-device`**: Device telemetry, clipboard, interactive Approve/Reject notifications, torch/haptics, Shizuku `rish` workflows, and `SystemGatewayServer`.
- **`android-browser`**: Headless mobile browser automation (`android_browser_automate`) for scraping, UI verification, screenshots, and localhost live preview.
- **`android-daemon`**: Autonomous background AI task scheduling, watchers, and ConnectRPC execution.

---

## 4. Mobile Host Capabilities (Voice, Share Sheet, Extra-Keys, Local Preview)

- **Voice Mode, Live Voice Call & Native Dictation:** Android `RECORD_AUDIO` and `MODIFY_AUDIO_SETTINGS` permissions support v2.34.0's built-in Live Voice Call (`/voice/ws` + `AudioWorklet`) backed by `VoiceForegroundService` (audio focus, speakerphone, persistent Mute/Stop notification controls) plus a one-tap native Android `SpeechRecognizer` Voice Dictation button in Quick Controls.
- **Android Share Sheet:** Antigravity registers `ACTION_SEND` and `ACTION_SEND_MULTIPLE` (text, images, and files). Shared files are copied into app cache (`cacheDir/shared/`) and delivered directly to the chat composer (or staged in a tap-to-paste banner).
- **Native Extra-Keys Bar & Quick Controls:** A toggleable native accessory bar above the soft keyboard provides `ESC`, `TAB`, sticky `CTRL`, arrows (`↑`, `↓`, `←`, `→`), `/`, `@`, `|`, and `~` for both xterm terminals (ANSI/control bytes) and chat slash commands / `@` mentions. The mobile Quick Controls pill offers one-tap toggles for **Desktop Mode**, **Extra-Keys Bar**, **Localhost Preview**, and **PC Remote Control**.
- **Safe Localhost Live Preview:** Local dev servers bound to `127.0.0.1` / `localhost` can be previewed via loopback iframes or the isolated standalone `LocalPreviewDialog` (no native `AndroidBridge` exposed). Arbitrary external `http://` URLs remain blocked.
- **Multi-Agent Swarms (`/swarm`) & Remote Sessions (v2.34.0+):** Built-in `native_swarm` backend, builtin swarm skills (`native_swarm`, `swarm_monitor`, `swarm_pane`, `swarm_narrator`), and the Swarm Board sidecar work natively without conflicting with Android seed skills (`android-device`, `android-browser`, `android-daemon`). Cloud **Remote Agents** are available under `Projects -> New Remote Project`, and controlling desktop PC/CLI sessions is available via Quick Controls -> **PC Remote Control** (opens `https://antigravity.google.com` in an in-app Custom Tab).

---

## 5. Package Management (`pkg`)

AGY includes an ultra-fast, native package manager (`pkg`) written in Bash & AWK:

- **Usage & Syntax:**
  ```bash
  # Update package indexes (run before installing if index is empty):
  pkg update

  # Install packages (do NOT pass -y flag; list package names directly):
  pkg install git python nodejs bash

  # Search, list, and query:
  pkg search <pattern>
  pkg list
  pkg installed
  pkg info <package>

  # Remove package:
  pkg remove <package>

  # Self-test system health:
  pkg selftest
  ```
- **Post-Install Shebang Fixes:**
  After installing global Python/Node scripts (`pip`, `npm`), fix hardcoded shebangs using:
  ```bash
  agy-fix-paths
  ```

---

## 6. Elevated Operations via Shizuku (`rish`)

The agent has native access to Shizuku's elevated ADB shell (`uid=2000(shell)`), either via the `android_shizuku_exec` MCP tool or directly via `$PREFIX/bin/rish`:
- **Executable Location:** `$PREFIX/bin/rish` (invoked via `rish -c "<command>"`).
- **Service Dependency:** Requires the Shizuku service to be running on the device. If `rish` reports `Server is not running`, ask the user to start Shizuku.
- **Common Elevated Operations:**
  1. **Restricted Filesystem Access:**
     ```bash
     rish -c "ls -la /storage/emulated/0/Android/data"
     rish -c "cp <src> <dest>"
     ```
  2. **APK & Package Management (`pm`):**
     ```bash
     rish -c "pm install -r /storage/emulated/0/.../app.apk"
     rish -c "pm list packages -3"
     rish -c "pm uninstall com.example.app"
     ```
  3. **Activity Management, Screenshots & Diagnostics (`am`, `screencap`, `logcat`, `dumpsys`):**
     ```bash
     rish -c "am start -n com.example.app/.MainActivity"
     rish -c "screencap -p /storage/emulated/0/Download/screen.png"
     rish -c "logcat -d -t 150"
     rish -c "dumpsys package com.example.app"
     ```
  4. **System Properties & Settings:**
     ```bash
     rish -c "getprop ro.build.version.release"
     rish -c "settings get global airplane_mode_on"
     ```

---

## 7. Development & Server Guidelines

- **Port Binding:** Local development servers (Vite, Next.js, Express, FastAPI) MUST bind to `localhost` or `127.0.0.1`.
- **Reserved Ports:** Ports `45157` (release core), `45158` (dev core), and `45160` (`SystemGatewayServer`) are reserved for Antigravity internals. Never bind dev servers to these ports.
- **Safe Dev Ports:** `3000`, `5173`, `8000`, `8080`.
- **Native Compilation:** When compiling native C/C++/Rust code, target `-march=armv8-a` baseline (ARMv8.0-A minimum, with runtime LSE emulation or native atomics).

---

## 8. Background Daemon & AI Task Manager (`agy-daemon`)

Antigravity includes an ultra-lightweight background scheduler, watcher, and AI task manager:
- **Footprint:** <10MB RAM, 0.0% CPU idle.
- **Persistence:** Runs alongside `CoreServerService` under Android Foreground Service Partial WakeLock (executes even with screen off or locked).
- **Real AI Execution:** Tasks configured with `--prompt` (and no `--command`) read `$HOME/logs/core.state.json` and dispatch directly to `language_server` via ConnectRPC (`StartCascade` + `SendUserCascadeMessage`), passing `workspaceUris` when `--project` is set or targeting an existing `--conversation-id`. If the core is unreachable, `agy-daemon` falls back safely to posting an actionable Android notification (`agy-notify`) without shell interpolation.

### Service Lifecycle
```bash
agy-daemon status    # Check daemon status, PID, and recent task execution logs
agy-daemon start     # Start daemon service
agy-daemon stop      # Stop daemon service
agy-daemon restart   # Restart daemon service
```

### AI Task Management (`agy-daemon task`)
Autonomous background jobs support structured execution and `--json` formatting (or manage via the `android_schedule_task` MCP tool):
```bash
# List tasks:
agy-daemon task list
agy-daemon task list --json
agy-daemon task list --active-only

# Add scheduled tasks:
agy-daemon task add --name "Review Code" --delay 30m --project "/storage/emulated/0/Documents/myapp" --prompt "Check test results and summarize changes"
agy-daemon task add --name "Disk Health" --interval 1h --command "df -h > ~/.gemini/disk.log"
agy-daemon task add --name "Nightly Clean" --cron "0 3 * * *" --command "rm -rf ~/.gemini/tmp/*"

# Event & Watcher Triggers:
agy-daemon task add --name "Watch PRs" --github-pr "owner/repo" --interval 5m --command "agy-notify post -t 'New PR' -m 'PR detected'"
agy-daemon task add --name "File Watcher" --watch-file "/path/to/project/build.gradle" --command "pkg selftest"
agy-daemon task add --name "Battery Alert" --battery-below 15 --command "agy-notify post -t 'Low Battery' -m 'Save your work'"

# Manage tasks:
agy-daemon task pause <task_id_or_name>
agy-daemon task resume <task_id_or_name>
agy-daemon task run-now <task_id_or_name>
agy-daemon task logs <task_id_or_name>
agy-daemon task remove <task_id_or_name>
agy-daemon clear
```

### Android Status Notifications (`agy-notify`)
Post or manage notifications directly in the Android status bar:
```bash
agy-notify post -t "Build Finished" -m "Tests passed without errors"
agy-notify list
agy-notify cancel <tag>
```

---

## 9. Project Architecture & Sync Rules (Documentation-First)

- **Documentation Sync:** Every module (`modules/*`, `app/`, `web/`, `env/`) must maintain an accurate, concise `README.md`. Any change to code, interfaces, or flags MUST be accompanied by immediate documentation updates.
- **No Hardcoded Crutches:** Avoid hardcoding paths or assumptions where dynamic calculation (`$PREFIX`, `$HOME`, `$PROJECT_ROOT`) is possible.
- **Clean State:** Keep working trees clean, avoid creating dead or duplicated state, and do not commit temporary build artifacts.
