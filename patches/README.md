# Antigravity core patch modules

Patch the official arm64 `language_server` core **on a staging copy** (never the
original archive member or `core/language_server`). Python 3 stdlib only.

Groups: `eligibility`, `seccomp`, `resolv`, `auth`. All modules are adaptive
(signature / pclntab, **no fixed offsets**), deterministic, idempotent and
fail-closed.

## Layout

```
patch_runner.py                 # thin entry point -> patch_lib.runner.main
patch_lib/                      # shared engine
  runner.py                     # GROUP_MODULES, run_pipeline, main
  loader.py                     # importlib group/module loading
  report.py                     # report dict schema
  module_base.py                # shared group scaffold
  hash.py  manifest.py  elf.py  scan.py  cli.py
groups/
  gates/     syscalls/     resolv/     auth/
    module.py                   # run(target_path, ...) + __main__
    signatures.py               # verbatim byte literals / regexes
    analyze.py                  # pure analysis
  resolv/ auth/ also have selftest.py (`_selftest`)
manifest.json                   # static description of groups/signatures
tests/test_patches.py           # stdlib unittest coverage
```

## Run

```bash
# apply every group to a staging copy
python3 patches/patch_runner.py /tmp/opencode/agy-patches/lang

# check a staged binary only, write nothing
python3 patches/patch_runner.py /tmp/opencode/agy-patches/lang --verify

# compute, report, write nothing
python3 patches/patch_runner.py /tmp/opencode/agy-patches/lang --dry-run

# tolerate a completely absent signature instead of failing (NOT fail-closed)
python3 patches/patch_runner.py <staged> --allow-missing
```

`patch_runner.py` prints the initial/final sha256 and writes
`runtime.manifest.json` next to the binary (override with `--manifest PATH`,
disable with `--no-manifest`). It exits non-zero if any group fails; with
`--verify` it exits non-zero unless every group verifies. A repeated apply on an
already-patched binary changes no bytes (identical sha256) and leaves the
manifest untouched.

Each group is runnable standalone **from the `patches/` directory**:

```bash
python3 -m groups.gates.module    <staged> [--verify|--dry-run|--allow-missing]
python3 -m groups.syscalls.module <staged> [--verify|--dry-run|--allow-missing]
python3 -m groups.resolv.module   <staged> [--verify|--dry-run|--allow-missing]
python3 -m groups.resolv.module   --selftest
python3 -m groups.auth.module     <staged> [--verify|--dry-run|--allow-missing]
python3 -m groups.auth.module     --selftest
```

Run the test suite from `patches/`:

```bash
python3 tests/test_patches.py
```

## Fail-closed policy

* A missing or unexpected signature count is a hard error (exit non-zero), and
  the binary is **not written**. `--allow-missing` downgrades a completely
  absent signature to a skip; partial/ambiguous state is always an error.
* `syscalls` never does a global scan of `MOVZ X0,#439/#452`: the constant
  appears elsewhere as protobuf/data. The instruction is matched **only inside
  the pclntab-resolved function body**, and only when it is unique. A pclntab
  that cannot resolve `runtime.memmove` aborts.
* The eligibility regexes are register specific (`w1` / `w3`); the `w2`
  channelz `(*ChannelTrace).clear` site can never match.
* `auth` asserts its `do_not_patch` OAuth strings before and after the rewrite.

## Re-deriving signatures when the core updates

1. Verify the new core is a 64-bit little-endian AArch64 ELF: magic `7f 45 4c
   46`, `e_machine=183`.
2. `groups/gates`: search `.text` for `01 20 40 39` (ldrb w1) followed by a
   `tbnz w1` (`.. .. .. 37`) and for `03 20 40 39` (ldrb w3) followed by
   `tbz w3,#0` (`.. .. .. 36`) and the token setup tail `.. 03 10 06 a9`.
   Re-confirm the replacement encodings with any AArch64 assembler
   (`mov w1,#1` = `21 00 80 52`, `mov w3,#1;strb w3,[x0,#8]` =
   `23 00 80 52 03 20 00 39`). Update `EXPECTED_COUNTS` and `manifest.json`.
3. `groups/syscalls` needs no offsets: it re-scans ELF headers, section headers
   and the Go pclntab (magic `f1 ff ff ff`). If a function name changes or the
   MOVZ cannot be uniquely located, the module fails — inspect with `--dry-run`
   before widening anything. `MOVZ X0,#imm` =
   `((0xd2800000 | (imm<<5))).to_bytes(4,'little')`; recompute expected counts
   for the new core.
4. `groups/resolv` / `groups/auth`: confirm the literal strings and their counts
   with `strings`/`grep -abo`; all replacements are length asserted.
