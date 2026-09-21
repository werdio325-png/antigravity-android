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
    echo "[*] Cleaning staging, temporary files and build artifacts..."
    rm -rf "$STAGING_DIR"/*
    rm -rf "$TOOLS_DIR"/notification_lab/build "$TOOLS_DIR"/overlay_panel/build
    rm -f "$TOOLS_DIR"/notification_lab/*.apk* "$TOOLS_DIR"/overlay_panel/*.apk*
    rm -f "$TOOLS_DIR"/*.o "$TOOLS_DIR"/lse_emulator.o
    rm -f "$PROJECT_ROOT"/screen*.png "$PROJECT_ROOT"/window*.png "$PROJECT_ROOT"/uidump*.xml "$PROJECT_ROOT"/boot_perf*.log
    echo "[+] Clean complete. All intermediate artifacts removed."
    exit 0
fi

if [ "$1" = "-h" ] || [ "$1" = "--help" ]; then
    echo "Antigravity Mobile Build Pipeline"
    echo "Usage: bash build.sh [options]"
    echo ""
    echo "Commands:"
    echo "  clean                 Remove staging and intermediate build artifacts"
    echo ""
    echo "Options:"
    echo "  --flavor <dev|prod>   Build flavor (dev: com.antigravity.mobile.dev, prod: com.antigravity.mobile)"
    echo "  --prod                Shortcut for --flavor prod"
    echo "  --dev                 Shortcut for --flavor dev (default)"
    echo "  --install             Automatically install APK after build (pm/rish)"
    echo "  --keep-staging        Keep intermediate compilation files in staging/build/"
    echo "  --patch-web           Repack web interface from web_ui/ into binary"
    echo "  --web-dir <dir>       Specify custom directory for web interface"
    echo "  --no-bypass-region    Disable region eligibility bypass patch"
    echo "  --no-armv8.0          Disable ARMv8.0 compatibility patch"
    echo "  -h, --help            Show this help message"
    echo ""
    echo "Environment Variables:"
    echo "  FLAVOR=<dev|prod>     Build flavor (default: dev)"
    echo "  OUTPUT_APK=<name>     Output APK filename (default: based on flavor)"
    exit 0
fi

KEEP_STAGING=false
INSTALL_AFTER_BUILD=false
FLAVOR="${FLAVOR:-dev}"
PATCH_ARGS=()

while [ $# -gt 0 ]; do
    case "$1" in
        --keep-staging)
            KEEP_STAGING=true
            shift
            ;;
        --install)
            INSTALL_AFTER_BUILD=true
            shift
            ;;
        --prod)
            FLAVOR="prod"
            shift
            ;;
        --dev)
            FLAVOR="dev"
            shift
            ;;
        --flavor)
            FLAVOR="$2"
            shift 2
            ;;
        --flavor=*)
            FLAVOR="${1#*=}"
            shift
            ;;
        *)
            PATCH_ARGS+=("$1")
            shift
            ;;
    esac
done

if [ "$FLAVOR" = "prod" ]; then
    PKG_NAME="com.antigravity.mobile"
    APP_LABEL="Antigravity"
    DEFAULT_APK="Antigravity-Mobile.apk"
else
    PKG_NAME="com.antigravity.mobile.dev"
    APP_LABEL="Antigravity Dev"
    DEFAULT_APK="Antigravity-Mobile-Dev.apk"
fi
TARGET_APK_NAME="${OUTPUT_APK:-$DEFAULT_APK}"

echo "========================================"
echo "    Antigravity Mobile Build Pipeline"
echo "    Flavor: $FLAVOR ($PKG_NAME)"
echo "    Target: $TARGET_APK_NAME"
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

# Генерация AndroidManifest.xml под выбранный flavor
sed -e "s/package=\"[^\"]*\"/package=\"$PKG_NAME\"/" \
    -e "s/android:label=\"[^\"]*\"/android:label=\"$APP_LABEL\"/" \
    "$APP_DIR/AndroidManifest.xml" > "$BUILD_DIR/AndroidManifest.xml"

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
if [ ! -d "$PROJECT_ROOT/web_ui" ] || [ ! -f "$PROJECT_ROOT/web_ui/index.html" ]; then
    echo "[*] web_ui не найден, автоматическое извлечение из ядра..."
    python3 "$PROJECT_ROOT/patches/patch_web.py" extract "$STAGING_DIR/language_server" "$PROJECT_ROOT/web_ui"
fi
if [ -d "$PROJECT_ROOT/web_ui" ]; then
    echo "[*] Применение патча сенсорных нажатий (выбор моделей и мышления)..."
    python3 "$PROJECT_ROOT/patches/patch_touch.py" "$PROJECT_ROOT/web_ui"
    echo "[*] Применение патча оффлайн-кеша проектов и обсуждений..."
    python3 "$PROJECT_ROOT/patches/patch_cache.py" "$PROJECT_ROOT/web_ui"
    echo "[*] Вшивание web_ui бандла в assets/web..."
    mkdir -p "$BUILD_DIR/apk/assets/web"
    cp -rf "$PROJECT_ROOT/web_ui/"* "$BUILD_DIR/apk/assets/web/"
fi

echo "[5/6] Генерация R.java и подготовка DEX..."
if command -v javac >/dev/null 2>&1 && command -v java >/dev/null 2>&1; then
    aapt package -f -m \
        --custom-package com.antigravity.mobile.dev \
        -J "$BUILD_DIR/gen" \
        -M "$BUILD_DIR/AndroidManifest.xml" \
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
elif [ -f "$RUNTIME_SRC/classes.dex" ]; then
    echo "[*] javac/java не обнаружены в системе. Используется предустановленный $RUNTIME_SRC/classes.dex"
    cp -f "$RUNTIME_SRC/classes.dex" "$BUILD_DIR/apk/classes.dex"
    echo "[6/6] Финальная упаковка..."
elif [ -f "$PROJECT_ROOT/staging/classes.dex" ]; then
    echo "[*] javac/java не обнаружены в системе. Используется $PROJECT_ROOT/staging/classes.dex"
    cp -f "$PROJECT_ROOT/staging/classes.dex" "$BUILD_DIR/apk/classes.dex"
    echo "[6/6] Финальная упаковка..."
else
    echo "[-] Ошибка: javac/java не найдены, и prebuilt classes.dex отсутствует!"
    exit 1
fi

aapt package -f \
    -M "$BUILD_DIR/AndroidManifest.xml" \
    -S "$APP_DIR/res" \
    -I "$ANDROID_JAR" \
    -F "$BUILD_DIR/unaligned.apk" \
    "$BUILD_DIR/apk"

cd "$BUILD_DIR/apk"
if command -v zip >/dev/null 2>&1; then
    zip -ur "$BUILD_DIR/unaligned.apk" lib/ classes.dex assets/
fi
cd "$PROJECT_ROOT"

zipalign -f -p 4 "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/aligned.apk"

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
    elif command -v rish >/dev/null 2>&1 && rish -c "cp '$OUTPUT_DIR/$TARGET_APK_NAME' /data/local/tmp/app.apk && chmod 644 /data/local/tmp/app.apk && pm install -r /data/local/tmp/app.apk && rm -f /data/local/tmp/app.apk" 2>/dev/null; then
        echo "[+] APK успешно установлен через Shizuku (rish)!"
    else
        echo "[!] Готовый APK доступен: $OUTPUT_DIR/$TARGET_APK_NAME"
        echo "    Установите его через файловый менеджер или команду pm install."
    fi
fi
