#!/data/data/com.termux/files/usr/bin/bash
set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODE="${1:-patched}"

if [ "$MODE" == "vanilla" ] || [ "$MODE" == "v" ]; then
    APK="$PROJECT_DIR/dist/Antigravity-v2.11.0-Vanilla.apk"
    TITLE="Vanilla (без патча региона, для чистого VPN)"
else
    APK="$PROJECT_DIR/dist/Antigravity-v2.11.0-Patched.apk"
    [ -f "$APK" ] || APK="$PROJECT_DIR/dist/Antigravity-v2.11.0.apk"
    TITLE="Patched (с патчем обхода региона)"
fi

if [ ! -f "$APK" ]; then
    echo "❌ APK не найден ($APK). Сначала выполните: bash build.sh"
    exit 1
fi

echo "📦 Скрытая установка APK [$TITLE] через Shizuku..."
cat "$APK" | rish -c "cat > /data/local/tmp/app.apk && pm install -r -d -t -g /data/local/tmp/app.apk && rm -f /data/local/tmp/app.apk"
echo "✅ Приложение com.antigravity.standalone [$TITLE] успешно установлено!"
