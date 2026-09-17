#!/usr/bin/env bash
set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE_SRC="$PROJECT_ROOT/core/language_server"
STAGING_DIR="$PROJECT_ROOT/staging"
RUNTIME_SRC="$PROJECT_ROOT/runtime/src"
APP_DIR="$PROJECT_ROOT/app"
OUTPUT_DIR="$PROJECT_ROOT/output"
TOOLS_DIR="$PROJECT_ROOT/tools"
BUILD_DIR="$STAGING_DIR/build"

ANDROID_JAR="$TOOLS_DIR/android.jar"
R8_JAR="$TOOLS_DIR/r8.jar"
KEYSTORE="$TOOLS_DIR/debug.keystore"

echo "========================================"
echo "    Antigravity Mobile Build Pipeline   "
echo "========================================"

if [ ! -f "$CORE_SRC" ]; then
    echo "[-] Ошибка: Ядро не найдено: $CORE_SRC"
    exit 1
fi

rm -rf "$BUILD_DIR"
mkdir -p "$STAGING_DIR" "$OUTPUT_DIR" "$BUILD_DIR/gen" "$BUILD_DIR/obj" "$BUILD_DIR/apk/lib/arm64-v8a" "$BUILD_DIR/apk/assets"

echo "[1/6] Подготовка staging окружения..."
cp -f "$CORE_SRC" "$STAGING_DIR/language_server"

echo "[2/6] Запуск patch pipeline..."
python3 "$PROJECT_ROOT/patches/patch_runner.py" "$STAGING_DIR/language_server" "$@"

echo "[3/6] Упаковка нативных библиотек..."
cp -f "$STAGING_DIR/language_server" "$BUILD_DIR/apk/lib/arm64-v8a/liblanguage_server.so"
if [ -d "$RUNTIME_SRC/glibc" ]; then
    cp -rf "$RUNTIME_SRC/glibc/"*.so* "$BUILD_DIR/apk/lib/arm64-v8a/" 2>/dev/null || true
fi
if [ -f "$TOOLS_DIR/liblse_emulator.so" ]; then
    cp -f "$TOOLS_DIR/liblse_emulator.so" "$BUILD_DIR/apk/lib/arm64-v8a/liblse_emulator.so"
fi

echo "[4/6] Упаковка runtime ресурсов в assets..."
for d in certs seed tools etc python; do
    if [ -d "$RUNTIME_SRC/$d" ]; then
        mkdir -p "$BUILD_DIR/apk/assets/runtime/$d"
        cp -rf "$RUNTIME_SRC/$d/"* "$BUILD_DIR/apk/assets/runtime/$d/"
    fi
done

echo "[5/6] Генерация R.java и компиляция Java..."
# Линкуем ресурсы с созданием базового APK
aapt package -f -m \
    -J "$BUILD_DIR/gen" \
    -M "$APP_DIR/AndroidManifest.xml" \
    -S "$APP_DIR/res" \
    -I "$ANDROID_JAR"

javac -encoding UTF-8 -d "$BUILD_DIR/obj" \
    -cp "$ANDROID_JAR" \
    "$BUILD_DIR/gen/com/antigravity/mobile/R.java" \
    "$APP_DIR/src/main/java/com/antigravity/mobile/"*.java

echo "[6/6] Преобразование в DEX (D8) и финальная упаковка..."
java -cp "$R8_JAR" com.android.tools.r8.D8 \
    --output "$BUILD_DIR/apk" \
    --lib "$ANDROID_JAR" \
    --min-api 24 \
    "$BUILD_DIR/obj/com/antigravity/mobile/"*.class

# Упаковываем base APK (assets уже находятся внутри $BUILD_DIR/apk/assets)
aapt package -f \
    -M "$APP_DIR/AndroidManifest.xml" \
    -S "$APP_DIR/res" \
    -I "$ANDROID_JAR" \
    -F "$BUILD_DIR/unaligned.apk" \
    "$BUILD_DIR/apk"

# Добавляем lib/arm64-v8a внутрь zip
cd "$BUILD_DIR/apk"
zip -ur "$BUILD_DIR/unaligned.apk" lib/
cd "$PROJECT_ROOT"

zipalign -f -p 4 "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/aligned.apk"

TARGET_APK_NAME="${OUTPUT_APK:-Antigravity-Mobile.apk}"
apksigner sign \
    --ks "$KEYSTORE" \
    --ks-key-alias androiddebugkey \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$OUTPUT_DIR/$TARGET_APK_NAME" \
    "$BUILD_DIR/aligned.apk"

apksigner verify "$OUTPUT_DIR/$TARGET_APK_NAME"

echo "========================================"
echo "[+] СБОРКА УСПЕШНО ЗАВЕРШЕНА!"
echo "Готовый APK: $OUTPUT_DIR/$TARGET_APK_NAME"
ls -lh "$OUTPUT_DIR/$TARGET_APK_NAME"
echo "========================================"
