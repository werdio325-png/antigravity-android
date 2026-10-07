---
name: android-environment
description: Essential guidelines and recipes for operating inside the Antigravity Android environment (AGY v2). Covers Android userspace tools, tlx/pkg package management, Shizuku (rish) ADB elevation, storage paths, and terminal execution.
---

# Antigravity Android Environment Guide

This skill guides the AI assistant on how to effectively operate, execute commands, and develop software within the **Antigravity for Android (AGY v2)** platform.

---

## 1. Environment Architecture & Constraints

- **Platform:** Android (Linux kernel 6.1+, ARM64 / `aarch64` architecture, ARMv8.1-A+ instruction set).
- **Application Context:** Runs within the `com.agy` package sandbox.
- **Terminal Execution Mode:**
  Standard desktop Linux bubblewrap (`bwrap`) isolation is **not supported** by the Android kernel/SELinux. Commands execute directly inside the application's userspace. Always run commands directly in the user shell.
- **Primary Paths:**
  - Application Home: `/data/user/0/com.agy/files` (`$HOME`)
  - Linux Prefix: `/data/user/0/com.agy/files/usr` (`$PREFIX`)
  - Binaries: `$PREFIX/bin`
  - Shared Storage (Projects): `/storage/emulated/0` (or `/sdcard`)
  - Conversation Brain/Artifacts: `/data/user/0/com.agy/files/.gemini/antigravity-app/brain/<conv-id>`

---

## 2. Package Management (`pkg` / `tlx`)

AGY v2 bundles an ultra-fast, native Debian package manager (`pkg`) written in Bash & AWK:

### Common Commands
```bash
# Update repository indexes
pkg update

# Check package health & repository configuration
pkg selftest

# Install development runtimes
pkg install git python nodejs clang make

# List installed packages
pkg installed

# Search for available packages
pkg search <pattern>

# Remove package
pkg remove <package>
```

### Shebang & Prefix Fixes
When compiling native packages or installing globally via `pip` or `npm`:
```bash
# Rewrite hardcoded paths across installed scripts
agy-fix-paths
```

---

## 3. Shizuku / Rish (Elevated ADB Shell)

If Shizuku is running on the device, the `rish` binary is accessible at `$PREFIX/bin/rish`. It provides the agent with **ADB Shell privileges (`uid=2000(shell)`)**, bypassing normal Android application storage and permission restrictions.

### How to Execute Commands via Shizuku
Execute commands using:
```bash
rish -c "<command>"
```

### Common Elevated Operations
1. **File System Operations on Shared Storage:**
   ```bash
   # Copy, move, or inspect files anywhere on /storage/emulated/0/
   rish -c "ls -la /storage/emulated/0/Download"
   rish -c "cp /storage/emulated/0/... /data/user/0/com.agy/files/..."
   ```

2. **Package Management (`pm`):**
   ```bash
   # Install a compiled APK directly on the device
   rish -c "pm install -r /storage/emulated/0/.../app.apk"
   
   # List installed packages
   rish -c "pm list packages -3"
   ```

3. **Activity Management & Diagnostics (`am`, `dumpsys`, `logcat`):**
   ```bash
   # Launch an application activity
   rish -c "am start -n com.example.app/.MainActivity"
   
   # View system logs
   rish -c "logcat -d -t 100"
   
   # Dump battery or memory status
   rish -c "dumpsys battery"
   ```

4. **Global System Settings (`settings`, `cmd`):**
   ```bash
   # Query system settings
   rish -c "settings get global airplane_mode_on"
   ```

---

## 4. Best Practices for Coding on Android

1. **Local Development Servers:**
   - Web servers (e.g. Vite, Express, FastAPI) should bind to `localhost` or `127.0.0.1`.
   - Use high-numbered ports (e.g., 3000, 5173, 8000, 8080) to avoid collisions with Antigravity core (ports 45157 / 45158).
2. **Path Encoding:**
   - Always quote paths when referencing external storage (Android paths frequently contain spaces, e.g. `antigravity v2`).
3. **Hardware Awareness:**
   - Compile code with `-march=armv8.1-a` or higher for optimal performance on the device's CPU.
