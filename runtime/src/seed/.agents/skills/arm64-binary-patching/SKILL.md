---
name: arm64-binary-patching
description: >-
  Expert guidelines, toolchains, and workflows for reverse engineering and binary patching
  ARM64 (AArch64) ELF executables and Go runtimes without source code.
---

# ARM64 Binary Patching & Reverse Engineering Guide

This skill equips the agent with best practices, opcode encoding rules, toolchains, and automation patterns for binary patching 64-bit ARM Linux/Android binaries (specifically Go runtimes, C++ native libraries, and stripped ELF executables).

---

## 1. The Patching Toolchain: Modern Alternatives to Hardcoded Hex

While raw byte replacement (`bytes.fromhex(...)` in Python) works, modern reverse engineering relies on more expressive and robust tools.

### A. Recommended Tool Combos

Tool | Role in Patching | Why It's Superior
:--- | :--- | :---
**Python + Keystone / Capstone** | Inline assembler/disassembler | Write human-readable assembly instructions (`ks.asm("mov x0, #48; ret")`) instead of manual hex calculations.
**Radare2 / Rizin (`r2`, `rasm2`)** | CLI reversing & in-place patching | Available natively in Android via `pkg install radare2`. Provides `rasm2` for instant opcode generation and `r2 -w` for interactive byte edits.
**Ghidra / IDA Pro** | Visual decompiler & xrefs locator | The primary discovery tool for finding target offsets, function boundaries, and callers.
**GoReSym** | Go symbol recovery | Recovers all Go function names, types, and line mappings from stripped binaries via `pclntab` in seconds.
**LIEF (`import lief`)** | ELF structural modification | Add new sections, code caves, modify `.dynamic` (`DT_NEEDED`), rewrite imports/exports without breaking ELF segment alignments.
**Patchelf** | Dynamic linker surgery | Quick CLI tool (`pkg install patchelf`) to change interpreter, replace needed `.so` dependencies, or expand rpath.

---

## 2. Fast Opcode Reference (ARM64 / AArch64 Little-Endian)

Instruction | Assembly Syntax | Raw Bytes (LE) | Hex Integer (32-bit LE)
:--- | :--- | :--- | :---
**Return** | `ret` | `\xc0\x03\x5f\xd6` | `0xd65f03c0`
**NOP** | `nop` | `\x1f\x20\x03\xd5` | `0xd503201f`
**Return True** | `mov w0, #1; ret` | `\x20\x00\x80\x52\xc0\x03\x5f\xd6` | `52800020 d65f03c0`
**Return False** | `mov w0, #0; ret` | `\x00\x00\x80\x52\xc0\x03\x5f\xd6` | `52800000 d65f03c0`
**Syscall Return 0** | `mov x0, #0; ret` | `\x00\x00\x80\xd2\xc0\x03\x5f\xd6` | `d2800000 d65f03c0`

### Calculating Branch Offsets (`B` and `BL`)
ARM64 instructions are always 4-byte aligned. Branch targets are encoded as an unsigned or signed 26-bit immediate offset divided by 4:
```python
def encode_b(from_addr: int, to_addr: int) -> bytes:
    offset = (to_addr - from_addr) >> 2
    opcode = 0x14000000 | (offset & 0x03FFFFFF)
    return struct.pack("<I", opcode)

def encode_bl(from_addr: int, to_addr: int) -> bytes:
    offset = (to_addr - from_addr) >> 2
    opcode = 0x94000000 | (offset & 0x03FFFFFF)
    return struct.pack("<I", opcode)
```

### Immediate Moves (`MOVZ`)
`MOVZ Xd, #imm16`:
```python
def encode_movz(reg: int, imm16: int, is_64: bool = True) -> bytes:
    base = 0xd2800000 if is_64 else 0x52800000
    opcode = base | ((imm16 & 0xffff) << 5) | (reg & 0x1f)
    return struct.pack("<I", opcode)
```

---

## 3. Using Radare2 / Rasm2 for Instant Patching

When working directly in terminal on Android (`pkg install radare2`):

1. **Assemble instructions to hex on the fly**:
   ```bash
   rasm2 -a arm -b 64 "ret"
   # Output: c0035fd6

   rasm2 -a arm -b 64 "mov x0, #48; ret"
   # Output: 000680d2c0035fd6
   ```

2. **Disassemble hex bytes**:
   ```bash
   rasm2 -d -a arm -b 64 "c0035fd6"
   # Output: ret
   ```

3. **In-place patch with r2**:
   ```bash
   r2 -w /path/to/binary
   # Inside r2:
   s 0x6df4750        # Seek to address
   wa ret             # Write instruction directly!
   q                  # Quit and save
   ```

---

## 4. Go Binary Internals & `pclntab`

Go binaries statically compile runtime mechanisms, symbol tables, and type descriptions into specific sections.
* **`pclntab` (Program Counter Line Table)**:
  - Go headers start with magic bytes: `\xfb\xff\xff\xff\x00\x00` (Go 1.18 - 1.20) or `\xfa\xff\xff\xff\x00\x00` (Go 1.20+).
  - Contains all function entry points, sizes, and string names even when stripped with `strip -s`.
* **Standard Go Runtime Interceptions**:
  - `runtime.schedinit`: Initializes CPU capabilities (e.g. `arm64HasATOMICS`).
  - `runtime.rt_sigaction`: Registers Linux signals via system calls. Filtering SIGILL or SIGSYS here prevents Go from crashing when encountering unsupported opcodes.
  - `_cgo_sigaction`: CGO signal hook gateway. Intercepting call sites redirects signal installation to custom filters.

---

## 5. Code Caves and Trampolines

When a replacement routine exceeds the original function space:
1. **Locate padding**: Look for trailing null bytes or `NOP` alignments at the end of `.text` or between function boundaries (e.g. 16-byte alignment gaps).
2. **Inject Trampoline**:
   - Write the new logic into the code cave.
   - Conclude with a jump back `B <original_pc + 4>` or `RET`.
   - In the target function, replace the original instruction with `BL <code_cave_addr>`.
