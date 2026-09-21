---
name: android-reversing-and-patching
description: >-
  Advanced procedures for Android system-level reverse engineering, Seccomp syscall patching,
  Bionic libc idiosyncrasies, and autonomous APK/DEX/SO modifications.
---

# Android Reverse Engineering & Runtime Patching Guide

This skill provides an in-depth reference for overcoming Android OS-level constraints when porting, patching, or running native Linux binaries, Go servers, and Android APKs.

---

## 1. Android Seccomp Filters & Syscall Trapping (`SIGSYS`)

Starting with Android 8.0+, all unprivileged apps run inside a strict Seccomp-BPF sandbox. Disallowed syscalls trigger `SECCOMP_RET_TRAP`, immediately terminating the app with `SIGSYS` (exit code 159).

### Common Offending Syscalls & Safe Replacements

Trapped Modern Syscall | Opcode (ARM64) | Android-Approved Fallback | Replacement Opcode
:--- | :--- | :--- | :---
`faccessat2` (439 / `0x1b7`) | `\xe0\x36\x80\xd2` | `faccessat` (48 / `0x30`) | `\x00\x06\x80\xd2`
`fchmodat2` (452 / `0x1c4`) | `\x80\x38\x80\xd2` | `fchmodat` (53 / `0x35`) | `\xa0\x06\x80\xd2`
`clone3` (435 / `0x1b3`) | `\x60\x36\x80\xd2` | `clone` (220 / `0xdc`) | `\x80\x1b\x80\xd2`

### Patching Go Syscall Lookups
In Go standard library (`os/exec.LookPath`), finding executables invokes `unix.Faccessat(..., AT_EACCESS)`. The Go runtime issues syscall 439 (`faccessat2`).
* Scan `.text` for `mov x0, #0x1b7` (`d28036e0`) inside `syscall.faccessat2` or `os/exec.findExecutable`.
* Replace with `mov x0, #0x30` (`d2800600`).

---

## 2. Bionic libc vs Glibc & Dynamic Linkers

Android does not use standard Glibc; it uses Google's Bionic libc:
* **Dynamic Linker**:
  - Android native: `/system/bin/linker64`
  - Linux ARM64 Glibc: `ld-linux-aarch64.so.1`
* **Injecting Custom Libraries with Patchelf**:
  To bundle precompiled helper libraries (like `liblse_emulator.so` or `libc.so.6`) without requiring `LD_PRELOAD`:
  ```bash
  patchelf --add-needed liblse_emulator.so target_binary
  ```

---

## 3. SELinux & W^X (Write XOR Execute) Policies

Android enforces strict W^X on `/data/data/<package>/`:
* **Constraint**: Executing binaries directly from app internal data (`/data/user/0/.../files/`) is blocked on modern Android (`EACCES` / Permission Denied).
* **Solutions**:
  1. **Package as `.so`**: Name binaries `lib*.so` and place them inside the APK's `lib/arm64-v8a/` directory. Android extracts them into `getApplicationInfo().nativeLibraryDir` with executable permissions.
  2. **Custom Linker Invocation**: Invoke the dynamic linker explicitly:
     ```bash
     /path/to/ld-linux-aarch64.so.1 /path/to/binary
     ```

---

## 4. DNS Resolution in Headless Android

Android has no `/etc/resolv.conf`. The system relies on Android's `netd` daemon and IPC properties (`getprop net.dns1`).
* Linux binaries looking for `/etc/resolv.conf` will fail to resolve hostnames.
* **Resolution**: Patch the string `/etc/resolv.conf` inside libc or the binary to point to an accessible file (e.g. `/sdcard/res.conf` or an app data path) containing valid nameservers:
  ```text
  nameserver 8.8.8.8
  nameserver 1.1.1.1
  ```
