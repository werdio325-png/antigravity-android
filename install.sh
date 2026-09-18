#!/usr/bin/env bash
set -e

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APK_PATH="$PROJECT_ROOT/output/Antigravity-Mobile-Dev.apk"

if [ ! -f "$APK_PATH" ]; then
    echo "[*] APK не найден, запускаем сборку..."
    "$PROJECT_ROOT/build.sh"
fi

if [ ! -f "$APK_PATH" ]; then
    echo "[-] Ошибка: Файл $APK_PATH не найден."
    exit 1
fi

echo "========================================="
echo " Установка Antigravity Mobile Dev"
echo " Package: com.antigravity.mobile.dev"
echo " APK: $APK_PATH"
echo "========================================="

# Способ 1: Shizuku (rish)
if command -v rish >/dev/null 2>&1 && rish -c "id" >/dev/null 2>&1; then
    echo "[*] Установка через Shizuku (rish)..."
    rish -c "pm install -r '$APK_PATH'"
    echo "[+] Установка успешно завершена через Shizuku!"
    exit 0
fi

# Способ 2: Прямой pm install (если root или shell)
if command -v pm >/dev/null 2>&1 && pm install -r "$APK_PATH" 2>/dev/null; then
    echo "[+] Установка успешно завершена через pm!"
    exit 0
fi

# Способ 3: Открытие системного установщика через am
if command -v am >/dev/null 2>&1; then
    echo "[*] Запуск системного установщика пакетов Android..."
    am start -a android.intent.action.VIEW -d "file://$APK_PATH" -t "application/vnd.android.package-archive" 2>/dev/null || true
fi

echo ""
echo "[i] Файл APK готов к ручной установке:"
echo "    $APK_PATH"
echo "    Вы можете открыть его в любом файловом менеджере."
