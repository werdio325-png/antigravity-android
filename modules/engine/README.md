# modules/engine — engine compatibility layer

Continuum does not hardcode a model list. Everything the app shows about models
comes from the `language_server` binary that is actually embedded in the APK.

## Why this exists

The upstream project ships a fixed `language_server` build. Which Gemini models
that build can offer is a property of the binary, and whether they are *enabled*
is a property of the signed-in account on Google's side. Those are two different
questions, and conflating them is what makes a fork either lie about models or
silently rot as Google ships new engines.

This module answers only the first question, offline and from the binary itself,
so the app can say “this engine does not ship that model” instead of pretending.

## Files

| File | Purpose |
| :--- | :--- |
| `catalog.py` | Extracts the model catalogue from an engine binary (two-phase scan, tier families, label confirmation). |
| `engine_probe.py` | Reports version, build tag, ELF arch, sha256, catalogue and patch compatibility. Output is packaged as `assets/runtime/engine_info.json`. |
| `upgrade_engine.py` | Validates a candidate engine archive, diffs its catalogue against the embedded one, reports patch compatibility and (with `--apply`) updates the pristine archive, patch specs and runtime hashes. |
| `tests/` | Unit tests over synthetic fixtures plus an opt-in end-to-end probe of the real archive. |

## Usage

```bash
# Describe the engine that is embedded right now (fast, no patch scan):
python3 modules/engine/engine_probe.py \
    --binary modules/core/pristine/language_server.tar.gz --no-patch-scan

# Full report including patch-signature compatibility (~30 s):
python3 modules/engine/engine_probe.py \
    --binary modules/core/pristine/language_server.tar.gz

# Evaluate a newer engine before adopting it:
python3 modules/engine/upgrade_engine.py --archive /path/to/new/language_server.tar.gz
python3 modules/engine/upgrade_engine.py --archive /path/to/new.tar.gz --apply
```

`--apply` only rewrites the pristine archive, `modules/patches/specs/*.json`
offsets and the hashes in `prepare_runtime.py`; it never downloads anything and
never edits a device. `prepare_runtime.py` still enforces the expected SHA-256 of
the staged binary, so an archive whose patched output does not match fails the
build.

## Availability policy

* `availability` in `engine_info.json` is always `server-decided`.
* A model present in the catalogue may still be unavailable for an account; the
  UI annotates disabled rows with “account” rather than enabling them.
* A model absent from the catalogue is reported as “engine” and can only appear
  after a newer engine build is adopted.

Nothing in this module talks to Google, and nothing bypasses authentication,
entitlements or regional restrictions.
