<h1 align="center">Antigravity Mobile (Android)</h1>

<p align="center">
  <b>Полностью автономный, 100% нативный ARM64 дистрибутив Google Antigravity в виде единого компактного Android APK без Termux, PRoot и виртуализации.</b>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-Apache%202.0-blue.svg" alt="License: Apache 2.0"></a>
  <a href="https://github.com/werdio325-png/antigravity-android/releases/latest"><img src="https://img.shields.io/github/v/release/werdio325-png/antigravity-android?color=orange&label=Release" alt="Latest Release"></a>
  <img src="https://img.shields.io/badge/Arch-ARM64--v8a-blue.svg" alt="Arch: ARM64">
  <img src="https://img.shields.io/badge/Platform-Android%207.0%2B-green.svg" alt="Platform: Android 7.0+">
  <img src="https://img.shields.io/badge/Engine-2.13.0-orange.svg" alt="Engine: 2.13.0">
  <img src="https://img.shields.io/badge/Status-Active-brightgreen.svg" alt="Status: Active">
</p>

<p align="center">
  <a href="#russian">🇷🇺 <b>Русский</b></a> &nbsp;|&nbsp; <a href="#english">🇬🇧 <b>English</b></a>
</p>

---

<a id="russian"></a>
## 🇷🇺 Описание проекта (Русский)

**Antigravity Mobile** — это открытый проект, упаковывающий полнофункциональный сервер **Google Antigravity Language Server** в единое нативное Android-приложение (APK). Больше не нужны сторонние эмуляторы терминалов (Termux), контейнеры PRoot или виртуализация: приложение устанавливается как обычный APK и работает прямо на железе вашего смартфона или планшета.

### 📥 Загрузка готовых APK (Releases v2.13.0)

Готовые установочные пакеты и нативные бинарники доступны в разделе [**GitHub Releases v2.13.0**](https://github.com/werdio325-png/antigravity-android/releases/latest):

* **[Antigravity-v2.13.0-BypassRegion.apk](https://github.com/werdio325-png/antigravity-android/releases/download/v2.13.0/Antigravity-v2.13.0-BypassRegion.apk)** — **Рекомендуемая версия**: снятие региональных экранов проверки Google (`--bypass-region`), автоматическая поддержка как новых процессоров (ARMv8.1+), так и старых чипов (ARMv8.0) с динамическим подключением эмулятора атомиков через `/proc/cpuinfo`, Shizuku (`rish`) и автономный рантайм.
* **[Antigravity-v2.13.0-Vanilla.apk](https://github.com/werdio325-png/antigravity-android/releases/download/v2.13.0/Antigravity-v2.13.0-Vanilla.apk)** — чистая оригинальная версия со стандартными региональными проверками Google и универсальной поддержкой любых ARM64 процессоров.
* **[antigravity-core-arm64-v2.13.0.tar.gz](https://github.com/werdio325-png/antigravity-android/releases/download/v2.13.0/antigravity-core-arm64-v2.13.0.tar.gz)** — сжатый архив оригинального ARM64-ядра `language_server` (48 МБ) для сборки из исходников.

---

### 🌟 Ключевые возможности

- **100% Native ARM64 (Универсальная поддержка всех чипов):**
  - Полная поддержка современных чипов (ARMv8.1+) и старых процессоров (ARMv8.0) из коробки. Сервис приложения на лету проверяет флаги `/proc/cpuinfo` (`atomics`) и подключает LSE-эмулятор только тогда, когда это действительно необходимо.
  - Никакой эмуляции системных вызовов (`ptrace`), замедляющей файловые операции и запуск процессов.
  - Нативное ядро пакуется как `liblanguage_server.so` в каталог `nativeLibraryDir`, строго соблюдая политики безопасности Android W^X (SELinux).
- **Мгновенный старт UI (Zero-Wait UI):**
  - При запуске моментально открывается чистый системный WebView с анимированным лоадером.
  - Ядро параллельно поднимается в защищённом `ForegroundService` с `WakeLock`. Как только локальный порт открыт — экран плавно переключается на рабочий стол Antigravity.
- **Бесшовный Google OAuth через Chrome Custom Tabs:**
  - Вход в Google-аккаунт перехватывается на лету и открывается в доверенном системном браузере (Chrome / Custom Tabs), полностью обходя запрет Google на авторизацию внутри WebViews.
  - После успешного входа Deep Link `antigravity://auth-success` мгновенно возвращает пользователя в приложение.
- **Встроенная автономная экосистема утилит:**
  - В APK зашит полный набор CLI-инструментов: `git`, `curl`, `ripgrep`, `python 3.14+`, `node`, `busybox`, `aapt`, `d8`, `apksigner`.
  - Встроен пакетный менеджер `pkg` для прямой загрузки пакетов без root и контейнеров.
- **Глубокая интеграция с Android через Shizuku (`rish`):**
  - Возможность управления системой без root-прав через Shizuku: установка и удаление пакетов (`pm`), снятие скриншотов (`screencap`), симуляция кликов и текста (`input tap / text`), чтение `logcat` и системных свойств.
- **Гибкий конвейер патчинга (Build & Patch Pipeline):**
  - Патчи ARMv8.0 включены по умолчанию и оптимизированы для стабильной работы на всех поколениях ядер.
  - Управление региональным патчем (`--no-bypass-region` для отключения).
  - Обход seccomp-фильтров ядра Android (`faccessat2` / `fchmodat2`).

---

### 🛠 Архитектура

```mermaid
graph TD
    subgraph APP["Android Application (com.antigravity.mobile)"]
        UI["MainActivity.java<br/>• Fullscreen WebView<br/>• Animated Splash & Loader<br/>• OAuth Custom Tab Bridge"]
        SVC["CoreServerService.java (Foreground Service)<br/>• WakeLock & Notification<br/>• Dynamic Linker (ld-linux-aarch64.so.1)<br/>• Dynamic /proc/cpuinfo atomics detector<br/>• DNS Config Generator (etc//resolv.conf)"]
        UI <-->|Localhost HTTPS :48999| SVC
    end

    subgraph RUNTIME["Autonomous Native Environment"]
        Core["liblanguage_server.so (Google ARM64)"]
        Glibc["Glibc & Dependencies (libc, libcurl, git, python3)"]
        Bridge["Shizuku Bridge (rish) & Android Shell"]
        SVC --> Core
        SVC --> Glibc
        Core <--> Bridge
    end
```

---

### 📦 Структура репозитория

```text
antigravity-mobile/
├── app/                                    # Исходный код Android APK (Java)
│   ├── AndroidManifest.xml                 # Манифест (ForegroundService dataSync, Deep Links)
│   ├── src/main/java/com/antigravity/mobile/
│   │   ├── MainActivity.java               # Жизненный цикл UI, перехват OAuth, WebView
│   │   └── CoreServerService.java          # Фоновый сервис ядра, автоопределение CPU, запуск
│   └── res/                                # Иконки, темы и network security config
│
├── patches/                                # Модули бинарного патчинга ядра
│   ├── patch_gates.py                      # Снятие региональных проверок
│   ├── patch_armv80.py                     # Безопасная эмуляция LSE-атомиков в .text
│   ├── patch_resolv.py                     # Патч DNS resolver (etc//resolv.conf)
│   ├── patch_syscalls.py                   # Seccomp bypass (faccessat2 / fchmodat2)
│   ├── patch_auth.py                       # Перехват Google OAuth и возврат через Deep Link
│   └── patch_runner.py                     # Оркестратор конвейера патчинга с SHA-256
│
├── runtime/                                # Нативный рантайм, пакуемый в assets
│   └── src/
│       ├── certs/ca-certificates.crt       # SSL корневые сертификаты
│       ├── etc/bashrc                      # Окружение Bash и алиасы вызовов через linker
│       ├── glibc/                          # Нативные ELF библиотеки ARM64
│       ├── python/stdlib.zip               # Стандартная библиотека Python
│       ├── seed/                           # Начальные настройки и конфигурации
│       └── tools/                          # CLI инструменты (git, curl, rg, rish, busybox)
│
├── tools/                                  # Инструменты сборки (android.jar, r8.jar, keystore)
├── build.sh                                # Главный скрипт сборки в один клик
└── README.md                               # Документация проекта
```

---

### 🚀 Инструкция по сборке

#### 1. Подготовка ядра
Поместите оригинальный 64-битный бинарник Google Antigravity `language_server` в каталог `core/`:
```bash
mkdir -p core
# Скопируйте language_server в core/language_server
```

#### 2. Запуск сборки
```bash
# Сборка универсальной версии со снятием региональных ограничений (BypassRegion):
./build.sh

# Сборка чистой Vanilla-версии со стандартными региональными проверками:
./build.sh --no-bypass-region
```

Готовый подписанный файл появится по пути: `output/Antigravity-Mobile.apk`.

---

<a id="english"></a>
## 🇬🇧 Project Description (English)

**Antigravity Mobile** is an open-source project packaging the complete **Google Antigravity Language Server** into a single, native Android APK. It eliminates the need for terminal emulators (Termux), PRoot containers, or virtualization: install the APK and run Antigravity directly on your smartphone or tablet hardware.

### 📥 Download Prebuilt APKs (Releases v2.13.0)

Prebuilt binaries are available in [**GitHub Releases v2.13.0**](https://github.com/werdio325-png/antigravity-android/releases/latest):

* **[Antigravity-v2.13.0-BypassRegion.apk](https://github.com/werdio325-png/antigravity-android/releases/download/v2.13.0/Antigravity-v2.13.0-BypassRegion.apk)** — **Recommended**: bypasses Google regional eligibility checks, supports all ARM64 generations (dynamic CPU detection for ARMv8.0 and ARMv8.1+), Shizuku (`rish`), and standalone CLI suite.
* **[Antigravity-v2.13.0-Vanilla.apk](https://github.com/werdio325-png/antigravity-android/releases/download/v2.13.0/Antigravity-v2.13.0-Vanilla.apk)** — Clean build with standard Google regional checks and universal CPU support.
* **[antigravity-core-arm64-v2.13.0.tar.gz](https://github.com/werdio325-png/antigravity-android/releases/download/v2.13.0/antigravity-core-arm64-v2.13.0.tar.gz)** — Core `language_server` binary archive (48 MB).

---

### 🌟 Key Highlights

- **Universal ARM64 Compatibility:**
  - Full support for modern ARMv8.1+ processors and legacy ARMv8.0 chips out of the box. Automatically detects CPU hardware atomics in `/proc/cpuinfo` and loads the LSE fallback emulator only when necessary.
- **Zero-Wait UI Launch:**
  - System WebView opens instantly with an animated loader upon launch.
  - Backend core initializes in a background `ForegroundService` with `WakeLock`. Once the localhost port is ready, the view smoothly cross-fades into the full Antigravity desktop.
- **Seamless Google OAuth via Chrome Custom Tabs:**
  - Google sign-in prompts are intercepted and redirected to the system browser (Chrome / Custom Tabs), bypassing Google's restrictions on in-WebView authentication.
  - Deep Link `antigravity://auth-success` automatically returns the user back to the application upon success.
- **Embedded Autonomous CLI Suite:**
  - Bundled with: `git`, `curl`, `ripgrep`, `python 3.14+`, `node`, `busybox`, `aapt`, `d8`, `apksigner`.
  - Built-in `pkg` tool for installing additional packages without root.
- **Android System Integration via Shizuku (`rish`):**
  - Shell access without root: manage packages (`pm`), capture screenshots (`screencap`), simulate input events (`input tap / text`), inspect logs (`logcat`).

---

### 🚀 Build Instructions

#### 1. Place Core Binary
Place your original Google Antigravity ARM64 binary into `core/`:
```bash
mkdir -p core
# Copy language_server into core/language_server
```

#### 2. Run the Build Script
```bash
# Build BypassRegion APK:
./build.sh

# Build Vanilla APK:
./build.sh --no-bypass-region
```

The resulting signed APK will be output to: `output/Antigravity-Mobile.apk`.

---

## 📜 License

Distributed under the **Apache License 2.0**. See [`LICENSE`](LICENSE) for more information.
