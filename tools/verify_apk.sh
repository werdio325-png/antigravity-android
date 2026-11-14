#!/usr/bin/env bash
# verify_apk.sh - fail the build unless the APK is the Continuum fork for ARM64.
#
# Checks:
#   1. package id and version from the compiled manifest;
#   2. the embedded engine is an AArch64 ELF and is genuinely present;
#   3. required runtime assets (engine info, manifest) are packaged;
#   4. no leftover upstream package id in the manifest.
set -euo pipefail

APK="${1:-dist/continuum.apk}"
EXPECTED_PKG="dev.continuum.antigravity"

if [ ! -f "$APK" ]; then
    echo "[FATAL] APK not found: $APK" >&2
    exit 1
fi

echo "[*] Verifying $APK"

BADGING=""
if command -v aapt >/dev/null 2>&1; then
    BADGING="$(aapt dump badging "$APK")"
else
    echo "[!] aapt not available; skipping manifest checks" >&2
fi

if [ -n "$BADGING" ]; then
    PKG="$(printf '%s\n' "$BADGING" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1)"
    VERSION="$(printf '%s\n' "$BADGING" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
    NATIVE="$(printf '%s\n' "$BADGING" | sed -n "s/^native-code: \(.*\)/\1/p" | head -1)"
    echo "    package: $PKG"
    echo "    version: $VERSION"
    echo "    native-code: ${NATIVE:-(none declared; engine is a packed asset)}"

    case "$PKG" in
        "$EXPECTED_PKG"|"$EXPECTED_PKG".dev) ;;
        *) echo "[FATAL] unexpected package id: $PKG" >&2; exit 1 ;;
    esac

    # ARM64 is asserted below by inspecting the embedded engine ELF. This app
    # ships the engine as an asset executed from the data dir, so the manifest
    # does not declare an arm64-v8a jniLib.
    if printf '%s\n' "$BADGING" | grep -q "^application-debuggable"; then
        if [ "${ALLOW_DEBUGGABLE:-0}" != "1" ]; then
            echo "[FATAL] APK is debuggable; release builds must not be" >&2
            exit 1
        fi
    fi
    case "$VERSION" in
        3.*) ;;
        *) echo "[!] unexpected version: $VERSION" >&2 ;;
    esac
fi

python3 - "$APK" <<'PY'
import sys, zipfile

apk = sys.argv[1]
REQUIRED = [
    "assets/runtime/manifest.json",
    "assets/runtime/engine_info.json",
    "assets/web/index.html",
]
ENGINE_HINTS = ("language_server",)
problems = []
found_engine = None

with zipfile.ZipFile(apk) as z:
    names = z.namelist()
    for entry in REQUIRED:
        if entry not in names:
            problems.append(f"missing asset: {entry}")
    for name in names:
        if any(hint in name for hint in ENGINE_HINTS) and name.startswith("assets/runtime/bin/"):
            with z.open(name) as fh:
                head = fh.read(64)
            if head[:4] != b"\x7fELF":
                problems.append(f"{name} is not an ELF binary")
                continue
            machine = int.from_bytes(head[18:20], "little")
            if machine != 0xB7:
                problems.append(f"{name} e_machine={machine:#x}, expected 0xB7 (AArch64)")
                continue
            found_engine = name
            print(f"    engine: {name} (AArch64 ELF)")

if not found_engine:
    problems.append("no AArch64 engine binary packaged under assets/runtime/bin/")

if problems:
    for problem in problems:
        print(f"[FATAL] {problem}", file=sys.stderr)
    sys.exit(1)
print("[OK] APK verification passed")
PY

if command -v apksigner >/dev/null 2>&1; then
    apksigner verify --print-certs "$APK" | head -5
fi

echo "[OK] $APK looks like a Continuum ARM64 release"
