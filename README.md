<div align="center">

  <!-- Language Switcher -->
  <p>
    <b>English</b> &nbsp;&bull;&nbsp; <a href="README.ru.md"><font color="#7d8590">Русский</font></a>
  </p>

  <br/>

  <img src="docs/assets/logo.png" alt="Antigravity Logo" width="96" height="96" style="border-radius: 22px; margin-bottom: 14px;" />

  <p><font size="6"><b>Antigravity for Android</b></font></p>

  <p><font color="#7d8590">Autonomous agentic development environment designed natively for Android and ARM64.</font></p>

  <p>
    <code>Android 7.0+</code> &nbsp;&bull;&nbsp;
    <code>ARM64 v8.1-A+</code> &nbsp;&bull;&nbsp;
    <code>Shizuku Ready</code> &nbsp;&bull;&nbsp;
    <code>Native Bionic</code> &nbsp;&bull;&nbsp;
    <code>Apache 2.0</code>
  </p>

  <br/>

  <!-- Hero Visual: Full Width & Theme-Adaptive -->
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/hero_dark.png">
    <source media="(prefers-color-scheme: light)" srcset="docs/assets/hero_light.png">
    <img alt="Antigravity Workspace Interface" src="docs/assets/hero_dark.png" width="100%" style="border-radius: 14px; border: 1px solid rgba(128, 128, 128, 0.2); box-shadow: 0 12px 32px rgba(0, 0, 0, 0.35);" />
  </picture>

</div>

---

<p><font size="5"><b>Overview</b></font></p>

**Antigravity for Android** brings Google’s autonomous AI pair programming platform natively to mobile and tablet hardware.

Unlike conversational chat interfaces or heavy virtualized containers, Antigravity integrates a native, self-contained Bionic Linux userspace directly alongside an autonomous agent core. The agent inspects codebases, modifies project trees, invokes local compilers, runs test suites, and verifies execution directly on the device.

> [!NOTE]
> **Native Speed with Zero Container Overhead:** Executes directly within the application userspace without PRoot, QEMU, or chroot translation layers.

---

<p><font size="5"><b>Key Capabilities</b></font></p>

<table width="100%" style="border-collapse: separate; border-spacing: 8px; border: none;">
  <tr>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>Autonomous Agent Engine</b></font></p>
      <p><font color="#7d8590">Direct integration with state-of-the-art Gemini models. Plans implementation steps, edits source trees, diagnoses compile errors, and executes multi-agent workflows autonomously.</font></p>
    </td>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>Elevated Execution (Shizuku & Rish)</b></font></p>
      <p><font color="#7d8590">Seamless rootless ADB privilege escalation (<code>uid=2000</code>). Grants full access to shared storage, package installation (<code>pm install</code>), and system diagnostic telemetry.</font></p>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>Embedded Package Management</b></font></p>
      <p><font color="#7d8590">Includes <code>pkg</code> (tlx) — an ultra-fast, minimal package manager implemented in pure POSIX shell and AWK. Bootstraps developer toolchains (<code>git</code>, <code>python</code>, <code>node</code>) in seconds.</font></p>
    </td>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>Adaptive Touch & Tablet Interface</b></font></p>
      <p><font color="#7d8590">Ergonomics tailored for mobile engineering: dynamic multi-pane layouts, virtual viewport keyboard tracking, system bar color synchronization, and gesture controls.</font></p>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>Low-Level AArch64 Patch Pipeline</b></font></p>
      <p><font color="#7d8590">In-engine binary rewriting pipeline providing seccomp syscall bypassing, thread-pointer register patching, and libc compatibility shims for unmodified Linux binaries.</font></p>
    </td>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>Deterministic Build Pipeline</b></font></p>
      <p><font color="#7d8590">Zero-Gradle compilation toolchain using direct <code>aapt</code>, <code>javac</code>, <code>d8</code>, and <code>apksigner</code> invocations. Assembles production-ready signed APKs in under four seconds.</font></p>
    </td>
  </tr>
</table>


---

<p><font size="5"><b>Hardware Requirements</b></font></p>

> [!WARNING]
> **Architecture Constraint: ARMv8.1-A or newer is required.**
> The underlying execution engine relies on 64-bit Large System Extension (LSE) atomic instructions. Legacy ARMv8.0 processors (Cortex-A53, Cortex-A57, Cortex-A72) are unsupported and will fault on initialization.

<table width="100%" style="border-collapse: separate; border-spacing: 0; border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; overflow: hidden;">
  <thead>
    <tr>
      <th style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">Component</th>
      <th style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">Minimum Specification</th>
      <th style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">Recommended Specification</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);"><b>CPU Architecture</b></td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);"><b>ARMv8.1-A+</b> (Cortex-A55, A75, Kryo 300+)</td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);"><b>ARMv8.2-A / ARMv9</b> (Snapdragon 8 Gen 1+, Dimensity, Tensor)</td>
    </tr>
    <tr>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);"><b>System Memory</b></td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">4 GB LPDDR4X</td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">8 GB+ LPDDR5</td>
    </tr>
    <tr>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);"><b>Storage</b></td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">2 GB (core runtime)</td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">8 GB+ (for local toolchains and build caches)</td>
    </tr>
    <tr>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);"><b>Operating System</b></td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">Android 7.0 (API 24)</td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">Android 12 – 16+</td>
    </tr>
    <tr>
      <td style="padding: 12px 16px;"><b>Access Rights</b></td>
      <td style="padding: 12px 16px;">Standard Application User</td>
      <td style="padding: 12px 16px;">Shizuku (Active ADB service)</td>
    </tr>
  </tbody>
</table>

---

<p><font size="5"><b>Quick Start</b></font></p>

<p><b>1. Installation</b></p>

Available release artifacts:
* `antigravity.apk` — Standard Production Release (Port 45157).
* `antigravity-bypass.apk` — Region Bypass Edition (Port 45157, includes eligibility/regional gate bypass).

<p><b>2. Permissions</b></p>

1. Open the application and grant **All Files Access** (`MANAGE_EXTERNAL_STORAGE`).
2. *(Optional)* Launch Shizuku and authorize Antigravity to enable ADB shell capabilities.

<p><b>3. Toolchain & Environment Verification</b></p>

Verify the runtime environment and install required packages:

```bash
# Execute runtime diagnostics
pkg selftest

# Update catalog and install base tools
pkg update
pkg install git python nodejs bash

# List installed packages
pkg installed
```

---

<p><font size="5"><b>Project Structure</b></font></p>

```text
antigravity-v2/
├── app/        # Android Native Host, WebView container, and Process Supervisor
├── build/      # Deterministic Zero-Gradle pipeline (aapt, javac, d8, apksigner)
├── bus/        # Shell bus, IPC protocol daemons, and environment compositors
├── config/     # Centralized environment definitions (app.env, paths.env, tools.env)
├── docs/       # Technical architecture specifications and implementation guides
├── env/        # Bionic toolchain, filesystem overlay, and tlx/pkg manager
├── patches/    # Binary patch pipeline (Go pclntab, seccomp bypass, AArch64 gates)
└── web/        # Frontend UI, touch ergonomics, and AndroidBridge
```

---

<p><font size="5"><b>License</b></font></p>

This project is licensed under the Apache License 2.0. Refer to the LICENSE file for terms.
