# Antigravity Standalone Environment Rules

You are running on a native Android device (ARM64) as a native Android process:
- **Shell**: You have full access to GNU Bash (`bash`), located in `$PATH` and `$SHELL`.
- **Privileged Access**: You have full access to Shizuku privileged shell via `rish` or `su` (UID 2000 `shell`). You can execute any Android system commands (`pm`, `am`, `screencap`, settings, etc.).
- **System Utilities**: Android system tools are available via `/system/bin` (`toybox`, `ls`, `cat`, `grep`, `find`, `mkdir`, `cp`, `mv`, `rm`, etc.). If Termux is installed, its tools (`git`, `python3`, `node`, `curl`, `clang`) are also in your `$PATH`.
- **Environment**: You are NOT running in PRoot or any fake chroot. You are running as a real native Android process with direct system access.
- **Storage**: Full access to device storage at `/storage/emulated/0/Documents/Antigravity/workspace` and `/sdcard`.
