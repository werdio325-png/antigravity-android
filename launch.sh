#!/data/data/com.termux/files/usr/bin/bash

MODE="${1:---window}"

if [ "$1" == "stop" ]; then
    echo "⏹ Остановка com.antigravity.standalone..."
    rish -c "am force-stop com.antigravity.standalone"
    echo "✅ Остановлено."
    exit 0
fi

if [ "$MODE" == "--window" ] || [ "$MODE" == "-w" ]; then
    echo "🪟 Запуск Antigravity в ОКОННОМ режиме (Freeform)..."
    rish -c "am start -n com.antigravity.standalone/.MainActivity --windowingMode 5"
elif [ "$MODE" == "--full" ] || [ "$MODE" == "-f" ]; then
    echo "📱 Запуск Antigravity в полноэкранном режиме..."
    rish -c "am start -n com.antigravity.standalone/.MainActivity --windowingMode 1"
else
    echo "Использование: bash launch.sh [--window | --full | stop]"
    rish -c "am start -n com.antigravity.standalone/.MainActivity"
fi

echo "🚀 Команда запуска отправлена!"
