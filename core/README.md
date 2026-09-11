# Antigravity Core Binaries (ARM64)

В данной директории размещаются нативные бинарные библиотеки и загрузчики архитектуры **ARM64-v8a** (Linux glibc/Android Bionic).

## 📁 Структура директории `core/arm64-v8a/`

| Файл | Описание | В репозитории |
|---|---|---|
| `libldlinux.so` | Динамический компоновщик `ld-linux-aarch64.so.1` (glibc 2.44) | ✅ Отслеживается в Git |
| `libandroid-shmem.so` | Эмулятор SysV SHM памяти для Android | ✅ Отслеживается в Git |
| `libserver-patched.so` | Ядро с патчем авторизации (`MANAGER_GATE_ARM64`), работает в связке с VPN | 📦 Загружается из Releases |
| `libserver-vanilla.so` | Чистое оригинальное ядро (для пользователей, кому VPN не нужен по локации) | 📦 Загружается из Releases |
| `libserver.so` | Активное ядро по умолчанию для сборки APK | 📦 Загружается из Releases |

## ℹ️ Почему ядра `libserver*.so` не хранятся в Git
Размер каждого скомпилированного нативного ядра составляет **~159 МБ**, что превышает лимит GitHub на одиночные файлы (100 МБ).

Ядра распространяются в готовом виде:
1. Внутри готовых собранных APK в разделе [GitHub Releases](https://github.com/werdio325-png/antigravity-android/releases).
2. Либо могут быть взяты из upstream-репозитория первоисточника: [wallentx/antigravity-cli-termux](https://github.com/wallentx/antigravity-cli-termux).
