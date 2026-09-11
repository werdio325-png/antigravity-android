# Antigravity Android

Автономный ARM64-клиент для Google Antigravity в виде Android-приложения (APK) с поддержкой оконного режима (Freeform) и фоновой службы.

Основан на наработках [antigravity-cli-termux](https://github.com/wallentx/antigravity-cli-termux) от [@wallentx](https://github.com/wallentx).

## Возможности

- **APK с графическим интерфейсом:** WebView-интерфейс, запуск без ручного поднятия Termux или chroot.
- **Плавающие окна (Freeform):** запуск в отдельном окне с изменением размера на Android 8.0+.
- **Фоновая служба (Foreground Service):** процесс не выгружается системой при блокировке экрана или сворачивании.
- **Два варианта ядра:**
  - `patched` (`libserver-patched.so`) — обход региональных ограничений без VPN (`MANAGER_GATE_ARM64`).
  - `vanilla` (`libserver-vanilla.so`) — оригинальное ядро для работы через VPN.
- **Встроенный rootfs:** glibc 2.44, Python 3, Git, cURL, jq, ripgrep, BusyBox.
- **Интеграция с системным шеллом:** поддержка Shizuku (`rish`) и root (`su`).
- **OAuth-мост:** открытие ссылок авторизации Google через системный браузер (`xdg-open`).

## Структура

```
antigravity-android/
├── app/          # Исходный код APK (MainActivity, EngineService)
├── assets/       # Ресурсы
├── config/       # Настройки среды и правила агентов
├── core/         # Библиотеки связки (libldlinux.so, libandroid-shmem.so)
├── rootfs/       # Runtime-окружение (упаковывается в APK)
├── build.sh      # Сборка APK
├── install.sh    # Установка через Shizuku / adb
└── launch.sh     # Запуск и управление
```

## Сборка

Для сборки в Termux требуются: `aapt`, `javac`, `d8`, `apksigner`.

```bash
# Обе версии (patched и vanilla):
bash build.sh all

# Только patched:
bash build.sh patched

# Только vanilla:
bash build.sh vanilla
```

Собранные файлы сохраняются в `dist/`.

## Установка

```bash
# Версия с обходом региона:
bash install.sh patched

# Чистая версия:
bash install.sh vanilla
```

## Запуск

```bash
# В плавающем окне (Freeform):
bash launch.sh --window

# На весь экран:
bash launch.sh --full

# Остановка приложения и сервиса:
bash launch.sh stop
```

## Лицензия

[MIT](LICENSE)
