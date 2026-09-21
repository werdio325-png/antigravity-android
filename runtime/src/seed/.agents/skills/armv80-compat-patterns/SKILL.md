---
name: armv80-compat-patterns
description: >-
  Techniques and patterns for making modern ARM64 binaries backward-compatible
  with legacy ARMv8.0-A CPUs (Cortex-A53/A57, Snapdragon 660/820, Exynos 8890).
---

# ARMv8.0 Compatibility & Legacy Hardware Adaptation

This skill documents architectural discrepancies between baseline ARMv8.0-A and modern revisions (ARMv8.1-A LSE, ARMv8.2-A DotProd, ARMv8.3-A RCpc), providing proven patching algorithms and signal-based emulation strategies.

---

## 1. Architectural Differences & Missing Extensions

Modern compilers (Go 1.18+, Clang 14+) target `armv8.1-a` or newer by default. On ARMv8.0 hardware, executing these instructions raises an illegal instruction signal (`SIGILL`).

Extension | Added In | Instructions | ARMv8.0 Impact
:--- | :--- | :--- | :---
**LSE (Large System Extensions)** | ARMv8.1-A | `CAS`, `CASP`, `SWP`, `LDADD`, `LDCLR`, `STADD` | Fatal `SIGILL`. Replaces classic LL/SC (`LDXR`/`STXR`).
**Dot Product** | ARMv8.2-A | `UDOT`, `SDOT` (Vector operations) | Fatal `SIGILL` in AI/ML libraries and accelerated codecs.
**RCpc (Release Consistency PC)** | ARMv8.3-A | `LDAPR`, `LDAPRB`, `LDAPRH` | Fatal `SIGILL`. Weaker acquire ordering than `LDAR`.

---

## 2. Binary Translation Patterns (Static Patching)

### Pattern 1: `LDAPR` -> `LDAR` Conversion
`LDAPR` (Load-AcquireRCpc Register) can be safely converted to standard ARMv8.0 `LDAR` (Load-Acquire Register). `LDAR` enforces stronger ordering, ensuring complete correctness.

* **Bitmask Identification**:
  `LDAPR` matches: `(instruction & 0x3fbfc000) == 0x38bfc000`
* **Conversion Algorithm**:
  ```python
  # Preserve register indices and size bits, mask in LDAR opcode
  ldar = (instruction & 0xc00003ff) | 0x08dffc00
  ```

### Pattern 2: `SWP` to `LDR` / `STR` (Non-contended Flag Exchanges)
Single-threaded initialization flags (such as `google_find_phdr` constructor) that use `SWP`:
```python
# Replace 20 bytes of LSE atomic swap with idempotent LDR/STR checks
ORIG_PHDR  = bytes.fromhex("290080520801e9f8480000b4c0035fd61f2003d5")
PATCH_PHDR = bytes.fromhex("090140f9490000b4c0035fd629008052090100f9")
```

### Pattern 3: Bypassing Processor Feature Fail-Fast Checks
Google binaries frequently test CPU ID registers or attempt speculative atomics in `.init_array`. If an illegal instruction traps, the binary aborts early.
* Locate the checking function in `.init_array`.
* Overwrite the function prologue with `ret` (`\xc0\x03\x5f\xd6`).

---

## 3. Dynamic User-Space Emulation via `SIGILL` (`lse_emulator.s`)

When static patching is infeasible (e.g. thousands of dynamic atomics in JIT or complex routines), install a user-space exception handler:

```
[Unsupported Instruction] -> Hardware traps -> Linux Kernel delivers SIGILL
                                                     |
                                            [sigill_handler]
                                                     |
                                   1. Inspect ucontext_t->uc_mcontext.pc
                                   2. Fetch instruction opcode
                                   3. Decode operation (LSE / DotProd / RCpc)
                                   4. Perform equivalent registers update
                                   5. Advance PC: pc += 4
                                   6. Return from signal (execution continues!)
```

### Critical Rules for Signal-Based Emulation:
1. **Always advance PC**: Must add `+4` to `ucontext_t.uc_mcontext.pc` before returning, otherwise the processor re-executes the faulting instruction indefinitely.
2. **Prevent Go / CGO Signal Overwrites**:
   - Go's `runtime.rt_sigaction` resets signal handlers during startup.
   - Filter `SIGILL` (signal #4) in Go's sigaction gate to prevent Go from overriding your custom emulator.
