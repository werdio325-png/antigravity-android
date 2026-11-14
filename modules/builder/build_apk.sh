#!/usr/bin/env bash
# build_apk.sh - Master orchestrator for building, dexing, packaging, and signing
# the Continuum APK (independent fork of Antigravity for Android).
#
# Usage:
#   bash build.sh                 # release-signed dist/continuum.apk
#   bash build.sh --dev           # debuggable dist/continuum-dev.apk (PKG_DEV)
#   bash build.sh --fast          # incremental asset staging
#   bash build.sh --out <path>    # explicit output path
#
# Signing (never committed): a release keystore is read from
#   $CONTINUUM_KEYSTORE / tools.env KEYSTORE, then keys/continuum-release.jks,
#   and finally the public AOSP debug key (clearly reported, local builds only).
#
# This fork deliberately has no "eligibility bypass" edition: it never disables
# Google's authentication, entitlement or regional checks.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
export BUILD_DIR="$PROJECT_ROOT/build"
export DIST_DIR="$PROJECT_ROOT/dist"
export APP_DIR="$PROJECT_ROOT/app"
export TOOLS_DIR="$PROJECT_ROOT/tools"

# On-device builds: prefer the fork's own sandbox, then upstream's.
for candidate in \
    "/data/user/0/dev.continuum.antigravity.dev/files" \
    "/data/user/0/dev.continuum.antigravity/files" \
    "/data/user/0/dev.continuum.antigravity.dev/files" \
    "/data/user/0/dev.continuum.antigravity/files"; do
    if [ -d "$candidate" ] && [ -w "$candidate" ]; then
        export HOME="$candidate"
        export PATH="$candidate/usr/bin:$PATH"
        break
    fi
done
export LANG=C.UTF-8
export LC_ALL=C.UTF-8

export FAST_BUILD=0
export DEV_MODE=0
# The fork is standard-only by policy.
export NO_BYPASS=1
export CUSTOM_PREFIX=""
export CUSTOM_OUT=""

while [ $# -gt 0 ]; do
    case "$1" in
        --fast)
            export FAST_BUILD=1
            shift
            ;;
        --dev|-d)
            export DEV_MODE=1
            shift
            ;;
        --bypass|--region-bypass)
            echo "[ERROR] This fork does not ship an eligibility-bypass edition." >&2
            echo "        Google authentication, entitlement and region checks are never bypassed." >&2
            exit 2
            ;;
        --no-bypass|--skip-gates|--standard)
            export NO_BYPASS=1
            shift
            ;;
        --prefix)
            export CUSTOM_PREFIX="$2"
            shift 2
            ;;
        --out|-o)
            export CUSTOM_OUT="$2"
            shift 2
            ;;
        *)
            echo "[WARNING] Unknown argument: $1" >&2
            shift
            ;;
    esac
done

BASE_PREFIX="${CUSTOM_PREFIX:-continuum}"

if [ -n "$CUSTOM_OUT" ]; then
    OUT_APK="$CUSTOM_OUT"
    EDITION_LABEL="CUSTOM OUTPUT ($OUT_APK)"
elif [ "$DEV_MODE" = "1" ]; then
    OUT_APK="$DIST_DIR/${BASE_PREFIX}-dev.apk"
    EDITION_LABEL="DEV EDITION (debuggable, PKG_DEV)"
else
    OUT_APK="$DIST_DIR/${BASE_PREFIX}.apk"
    EDITION_LABEL="RELEASE EDITION (standard Google gates)"
fi

echo "============================================================"
echo "  CONTINUUM FOR ANDROID — APK BUILD PIPELINE"
echo "  $EDITION_LABEL"
if [ "$FAST_BUILD" = "1" ]; then
    echo "  [MODE: FAST ITERATION (UNCOMPRESSED ASSETS & CACHE)]"
fi
echo "============================================================"

# Check host tools
for tool in python3 aapt javac java apksigner; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "[ERROR] Missing required build tool: $tool" >&2
        echo "        See docs/BUILDING.md for the host toolchain." >&2
        exit 1
    fi
done

# Clean previous build artifacts
echo "[*] Cleaning build directories..."
rm -rf "$BUILD_DIR/gen" "$BUILD_DIR/res" "$BUILD_DIR/obj" "$BUILD_DIR/dex" "$BUILD_DIR/staging" \
       "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/aligned.apk"
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/res" "$BUILD_DIR/obj" "$BUILD_DIR/dex" "$BUILD_DIR/staging" "$DIST_DIR"

# Phase 01: Config Generation (creates BuildConfig.java, gen.xml, and build/AndroidManifest.xml)
echo -e "\n>>> [1/8] Generating Configs..."
python3 "$SCRIPT_DIR/01_gen_config.py" "$BUILD_DIR"

TARGET_MANIFEST="$BUILD_DIR/AndroidManifest.xml"

# Phase 02: Generate R.java
echo -e "\n>>> [2/8] Generating R.java..."
bash "$SCRIPT_DIR/02_r_java.sh" "$BUILD_DIR/gen" "$TARGET_MANIFEST" "$APP_DIR/src/main/res" "$TOOLS_DIR/android.jar" "$BUILD_DIR/res"

# Phase 03: Compile Java Sources
echo -e "\n>>> [3/8] Compiling Java Sources..."
bash "$SCRIPT_DIR/03_compile_java.sh" "$BUILD_DIR/obj" "$BUILD_DIR/gen" "$APP_DIR/src/main/java" "$TOOLS_DIR/android.jar"

# Phase 04: D8 Dexing
echo -e "\n>>> [4/8] Converting Bytecode to classes.dex (D8)..."
bash "$SCRIPT_DIR/04_dex.sh" "$BUILD_DIR/dex" "$BUILD_DIR/obj" "$TOOLS_DIR/r8.jar" "$TOOLS_DIR/android.jar" 24

# Phase 05: Stage Assets
echo -e "\n>>> [5/8] Staging Runtime and Web Assets..."
python3 "$SCRIPT_DIR/05_stage_assets.py" "$BUILD_DIR/staging" --no-bypass

# Phase 06: Package Unaligned APK
echo -e "\n>>> [6/8] Packaging Unaligned APK..."
bash "$SCRIPT_DIR/06_package.sh" "$BUILD_DIR/unaligned.apk" "$TARGET_MANIFEST" "$APP_DIR/src/main/res" "$TOOLS_DIR/android.jar" "$BUILD_DIR/staging/assets" "$BUILD_DIR/dex/classes.dex" "$BUILD_DIR/res"

# Phase 07: ZipAlign
echo -e "\n>>> [7/8] ZipAligning APK..."
python3 "$SCRIPT_DIR/align.py" "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/aligned.apk" 4

# Phase 08: Sign and Verify APK
echo -e "\n>>> [8/8] Signing and Verifying APK..."
bash "$SCRIPT_DIR/08_sign.sh" "$BUILD_DIR/aligned.apk" "$OUT_APK"
bash "$SCRIPT_DIR/09_verify.sh" "$OUT_APK"

echo "============================================================"
echo "  BUILD SUCCESSFUL! APK CREATED:"
echo "  $OUT_APK ($(stat -c%s "$OUT_APK") bytes)"
echo "  sha256: $(python3 -c 'import hashlib,sys;print(hashlib.sha256(open(sys.argv[1],"rb").read()).hexdigest())' "$OUT_APK")"
echo "============================================================"
