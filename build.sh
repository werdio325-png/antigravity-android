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

# --- CLI & Clean Handler ---
if [ "$1" = "clean" ]; then
    echo "[*] Cleaning staging and intermediate build artifacts..."
    rm -rf "$STAGING_DIR"/*
    rm -rf "$TOOLS_DIR"/notification_lab/build "$TOOLS_DIR"/overlay_panel/build
    rm -f "$TOOLS_DIR"/lse_emulator.o
    echo "[+] Clean complete. All intermediate artifacts removed."
    exit 0
fi

if [ "$1" = "-h" ] || [ "$1" = "--help" ]; then
    echo "Antigravity Mobile Build Pipeline"
    echo "Usage: ./build.sh [options]"
    echo ""
    echo "Commands:"
    echo "  clean                 Remove staging and intermediate build artifacts"
    echo ""
    echo "Options:"
    echo "  --install             Automatically install APK after build (pm/rish)"
    echo "  --keep-staging        Keep intermediate compilation files in staging/build/"
    echo "  --patch-web           Repack web interface from web_ui/ into binary"
    echo "  --web-dir <dir>       Specify custom directory for web interface"
    echo "  --no-bypass-region    Disable region eligibility bypass patch"
    echo "  --no-armv8.0          Disable ARMv8.0 compatibility patch"
    echo "  -h, --help            Show this help message"
    echo ""
    echo "Environment Variables:"
    echo "  OUTPUT_APK=<name>     Output APK filename (default: Antigravity-Mobile-Dev.apk)"
    exit 0
fi

KEEP_STAGING=false
INSTALL_AFTER_BUILD=false
PATCH_ARGS=()
for arg in "$@"; do
    if [ "$arg" = "--keep-staging" ]; then
        KEEP_STAGING=true
    elif [ "$arg" = "--install" ]; then
        INSTALL_AFTER_BUILD=true
    else
        PATCH_ARGS+=("$arg")
    fi
done

echo "========================================"
echo "    Antigravity Mobile Dev Build Pipeline"
echo "========================================"

if [ ! -f "$CORE_SRC" ]; then
    ARCHIVE=$(ls "$PROJECT_ROOT/core/"*.tar.gz 2>/dev/null | head -n 1 || true)
    if [ -n "$ARCHIVE" ] && [ -f "$ARCHIVE" ]; then
        echo "[*] Обнаружен сжатый архив ядра: $ARCHIVE. Распаковка..."
        tar -xzf "$ARCHIVE" -C "$PROJECT_ROOT/core"
    fi
fi

if [ ! -f "$CORE_SRC" ]; then
    echo "[-] Ошибка: Ядро не найдено: $CORE_SRC"
    echo "    Поместите core/language_server или core/antigravity-core-arm64.tar.gz"
    exit 1
fi

rm -rf "$BUILD_DIR"
mkdir -p "$STAGING_DIR" "$OUTPUT_DIR" "$BUILD_DIR/gen" "$BUILD_DIR/obj" "$BUILD_DIR/apk/lib/arm64-v8a" "$BUILD_DIR/apk/assets"

echo "[1/6] Подготовка staging окружения..."
cp -f "$CORE_SRC" "$STAGING_DIR/language_server"

echo "[2/6] Запуск patch pipeline..."
python3 "$PROJECT_ROOT/patches/patch_runner.py" "$STAGING_DIR/language_server" "${PATCH_ARGS[@]}"

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
aapt package -f -m \
    -J "$BUILD_DIR/gen" \
    -M "$APP_DIR/AndroidManifest.xml" \
    -S "$APP_DIR/res" \
    -I "$ANDROID_JAR"

javac -encoding UTF-8 -d "$BUILD_DIR/obj" \
    -cp "$ANDROID_JAR:$TOOLS_DIR/webkit.jar:$TOOLS_DIR/annotation.jar" \
    $(find "$BUILD_DIR/gen" -name "R.java") \
    $(find "$APP_DIR/src/main/java" -name "*.java")

echo "[6/6] Преобразование в DEX (D8) и финальная упаковка..."
java -cp "$R8_JAR" com.android.tools.r8.D8 \
    --output "$BUILD_DIR/apk" \
    --lib "$ANDROID_JAR" \
    --min-api 24 \
    $(find "$BUILD_DIR/obj" -name "*.class") \
    "$TOOLS_DIR/webkit.jar" \
    "$TOOLS_DIR/annotation.jar"

aapt package -f \
    -M "$APP_DIR/AndroidManifest.xml" \
    -S "$APP_DIR/res" \
    -I "$ANDROID_JAR" \
    -F "$BUILD_DIR/unaligned.apk" \
    "$BUILD_DIR/apk"

cd "$BUILD_DIR/apk"
zip -ur "$BUILD_DIR/unaligned.apk" lib/ classes.dex assets/
cd "$PROJECT_ROOT"

zipalign -f -p 4 "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/aligned.apk"

TARGET_APK_NAME="${OUTPUT_APK:-Antigravity-Mobile-Dev.apk}"
apksigner sign \
    --ks "$KEYSTORE" \
    --ks-key-alias androiddebugkey \
    --ks-pass pass:android \
    --key-pass pass:android \
    --out "$OUTPUT_DIR/$TARGET_APK_NAME" \
    "$BUILD_DIR/aligned.apk"

apksigner verify "$OUTPUT_DIR/$TARGET_APK_NAME"

# Пост-сборочная оптимизация дискового пространства
if [ "$KEEP_STAGING" = false ]; then
    echo "[*] Очистка промежуточных бинарников сборки..."
    rm -rf "$BUILD_DIR/obj" "$BUILD_DIR/gen" "$BUILD_DIR/apk/lib" "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/aligned.apk" "$STAGING_DIR/language_server"
fi

echo "========================================"
echo "[+] СБОРКА УСПЕШНО ЗАВЕРШЕНА!"
echo "Готовый APK: $OUTPUT_DIR/$TARGET_APK_NAME"
ls -lh "$OUTPUT_DIR/$TARGET_APK_NAME"
echo "========================================"

if [ "$INSTALL_AFTER_BUILD" = true ]; then
    echo "[*] Установка APK в систему..."
    if command -v pm >/dev/null 2>&1 && pm install -r "$OUTPUT_DIR/$TARGET_APK_NAME" 2>/dev/null; then
        echo "[+] APK успешно установлен через pm install!"
    elif command -v rish >/dev/null 2>&1 && rish -c "pm install -r '$OUTPUT_DIR/$TARGET_APK_NAME'" 2>/dev/null; then
        echo "[+] APK успешно установлен через Shizuku (rish)!"
    else
        echo "[!] Готовый APK доступен: $OUTPUT_DIR/$TARGET_APK_NAME"
        echo "    Установите его через файловый менеджер или команду pm install."
    fi
fi
