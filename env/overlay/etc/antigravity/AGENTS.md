# Antigravity Android Agent Rule

> [!NOTE]
> This rule is automatically loaded into every conversation in Antigravity for Android.

## Environment & Platform Identity
- You are running inside **Antigravity for Android (AGY v2)** on an ARM64 device (ARMv8.1-A+, Linux kernel 6.1+, Android 16).
- Application Root: `/data/user/0/com.agy/files` (`$HOME`).
- Linux Userspace Prefix: `/data/user/0/com.agy/files/usr` (`$PREFIX`).
- Shared Storage: `/storage/emulated/0` (projects typically reside under `/storage/emulated/0/Documents/`).

## Terminal Execution Guidelines
- **No Bubblewrap Sandbox:** Standard Linux desktop bubblewrap (`bwrap`) is not supported by the Android kernel/SELinux. All shell commands run directly in the application userspace.
- **Package Management:** Use the bundled `pkg` package manager (`pkg update`, `pkg install <name>`, `pkg installed`, `pkg selftest`).
- **Path Quoting:** Android storage paths frequently contain spaces (e.g. `/storage/emulated/0/Documents/antigravity v2/`). Always quote file paths in commands.

## Elevated Operations (Shizuku & Rish)
- If Shizuku is active on the device, the `rish` executable is available at `$PREFIX/bin/rish`.
- Execute ADB-level commands via `rish -c "<command>"`.
- Use `rish` when you need to:
  - Read/write system or shared files blocked by Android sandbox permissions.
  - Install or manage APK packages (`pm install`, `pm uninstall`).
  - Inspect Android services, logs, or activities (`am start`, `dumpsys`, `logcat`).
