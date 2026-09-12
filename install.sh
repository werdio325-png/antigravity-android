#!/data/data/com.termux/files/usr/bin/bash
set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODE="${1:-universal}"

if [ "$MODE" == "vanilla" ] || [ "$MODE" == "v" ]; then
    APK="$PROJECT_DIR/dist/Antigravity-v2.11.0-Vanilla.apk"
    TITLE="Vanilla (чистая версия, ARMv8.1+)"
else
    APK="$PROJECT_DIR/dist/Antigravity-v2.11.0-Universal.apk"
    [ -f "$APK" ] || APK="$PROJECT_DIR/dist/Antigravity-v2.11.0.apk"
    TITLE="Universal (универсальная версия, ARMv8.0 и ARMv8.1+)"
fi

if [ ! -f "$APK" ]; then
    echo "APK не найден ($APK). Сначала выполните: bash build.sh"
    exit 1
fi

echo "Скрытая установка APK [$TITLE] через Shizuku..."
cat "$APK" | rish -c "cat > /data/local/tmp/app.apk && pm install -r -d -t -g /data/local/tmp/app.apk && rm -f /data/local/tmp/app.apk"
echo "Приложение com.antigravity.standalone [$TITLE] успешно установлено!"
