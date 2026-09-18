#!/usr/bin/env bash
set -e

OVERLAY_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$OVERLAY_DIR/../.." && pwd)"

ANDROID_JAR="$PROJECT_ROOT/tools/android.jar"
R8_JAR="$PROJECT_ROOT/tools/r8.jar"
KEYSTORE="$PROJECT_ROOT/tools/debug.keystore"
BUILD_DIR="$OVERLAY_DIR/build"
OUTPUT_APK="$OVERLAY_DIR/DevOverlay.apk"

if [ "$1" = "clean" ]; then
    echo "[*] Cleaning Overlay Panel build artifacts..."
    rm -rf "$BUILD_DIR"
    echo "[+] Clean complete."
    exit 0
fi

echo "========================================="
echo "   Building Dev Overlay Panel APK        "
echo "========================================="

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/obj" "$BUILD_DIR/apk"

echo "[1/4] Generating R.java and base resources..."
aapt package -f -m     -J "$BUILD_DIR/gen"     -M "$OVERLAY_DIR/AndroidManifest.xml"     -S "$OVERLAY_DIR/res"     -I "$ANDROID_JAR"

echo "[2/4] Compiling Java classes..."
javac -encoding UTF-8 -d "$BUILD_DIR/obj"     -cp "$ANDROID_JAR"     "$BUILD_DIR/gen/com/antigravity/overlay/R.java"     "$OVERLAY_DIR/src/com/antigravity/overlay/"*.java

echo "[3/4] Compiling to DEX (D8)..."
java -cp "$R8_JAR" com.android.tools.r8.D8     --output "$BUILD_DIR/apk"     --lib "$ANDROID_JAR"     --min-api 26     "$BUILD_DIR/obj/com/antigravity/overlay/"*.class

echo "[4/4] Packaging and signing APK..."
aapt package -f     -M "$OVERLAY_DIR/AndroidManifest.xml"     -S "$OVERLAY_DIR/res"     -I "$ANDROID_JAR"     -A "$OVERLAY_DIR/assets"     -F "$BUILD_DIR/unaligned.apk"

cd "$BUILD_DIR/apk"
zip -uj "$BUILD_DIR/unaligned.apk" classes.dex
cd "$OVERLAY_DIR"

zipalign -f -p 4 "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/aligned.apk"

apksigner sign     --ks "$KEYSTORE"     --ks-key-alias androiddebugkey     --ks-pass pass:android     --key-pass pass:android     --out "$OUTPUT_APK"     "$BUILD_DIR/aligned.apk"

apksigner verify "$OUTPUT_APK"

echo "========================================="
echo "[+] СБОРКА УСПЕШНА!"
echo "Готовый тестовый APK: $OUTPUT_APK"
ls -lh "$OUTPUT_APK"
echo "========================================="
