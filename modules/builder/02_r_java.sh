#!/usr/bin/env bash
# 02_r_java.sh - Generates R.java from Android resources using aapt.
#
# Java sources always reference the base package (PKG in config/app.env), so a
# dev build (manifest package = PKG_DEV) additionally generates the base-package
# R class as a --custom-package fallback.
set -euo pipefail

export ANDROID_DATA="${ANDROID_DATA:-/data}"

GEN_DIR="${1:-$BUILD_DIR/gen}"
MANIFEST="${2:-$APP_DIR/src/main/AndroidManifest.xml}"
RES_DIR="${3:-$APP_DIR/src/main/res}"
ANDROID_JAR="${4:-$TOOLS_DIR/android.jar}"
BUILD_RES_DIR="${5:-${BUILD_DIR:-}/res}"

PKG=$(sed -n -E 's/.*package="([^"]+)".*/\1/p' "$MANIFEST" | head -n 1)

project_root="${PROJECT_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
BASE_PKG=$(sed -n -E 's/^PKG=([^[:space:]]+).*/\1/p' "$project_root/config/app.env" | head -n 1)
[ -n "$BASE_PKG" ] || { echo "Error: cannot read PKG from config/app.env" >&2; exit 1; }

RES_ARGS=()
if [ -n "${BUILD_RES_DIR:-}" ] && [ -d "$BUILD_RES_DIR" ]; then
    RES_ARGS+=(-S "$BUILD_RES_DIR")
fi
RES_ARGS+=(-S "$RES_DIR" --auto-add-overlay)

echo "[*] [02_r_java] Generating R.java for $PKG..."
mkdir -p "$GEN_DIR"
aapt package -f -m \
    -J "$GEN_DIR" \
    -M "$MANIFEST" \
    "${RES_ARGS[@]}" \
    -I "$ANDROID_JAR"

# The Java sources reference $BASE_PKG.R / $BASE_PKG.BuildConfig regardless of
# the manifest package (dev builds change only the manifest package).
if [ "$PKG" != "$BASE_PKG" ]; then
    echo "[*] [02_r_java] Generating fallback $BASE_PKG.R for package $PKG..."
    aapt package -f -m \
        -J "$GEN_DIR" \
        -M "$MANIFEST" \
        "${RES_ARGS[@]}" \
        -I "$ANDROID_JAR" \
        --custom-package "$BASE_PKG"
fi

echo "[OK] [02_r_java] R.java generated in $GEN_DIR"
