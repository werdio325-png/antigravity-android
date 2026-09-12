<p align="center">
  <img src="assets/mascot.png" width="140" height="140" alt="Antigravity Android Mascot" style="border-radius: 28px;">
</p>

<h1 align="center">Google Antigravity Standalone for Android</h1>

<p align="center">
  <b>Автономный ARM64-клиент для Google Antigravity с поддержкой Freeform-окон, фонового сервиса и независимой среды выполнения</b>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="License: MIT"></a>
  <a href="https://github.com/werdio325-png/antigravity-android/releases/latest"><img src="https://img.shields.io/github/v/release/werdio325-png/antigravity-android?color=orange&label=Release" alt="Latest Release"></a>
  <img src="https://img.shields.io/badge/Arch-ARM64--v8a-blue.svg" alt="ARM64">
  <img src="https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Engine-2.11.0-orange.svg" alt="Engine 2.11.0">
  <img src="https://img.shields.io/badge/Status-Release-brightgreen.svg" alt="Status: Release">
</p>

---

## Загрузка готовых APK (Releases)

Готовые установочные пакеты и нативные бинарники доступны в разделе [**GitHub Releases v2.11.0**](https://github.com/werdio325-png/antigravity-android/releases/latest):

* **[Antigravity-v2.11.0-Universal.apk](https://github.com/werdio325-png/antigravity-android/releases/download/v2.11.0/Antigravity-v2.11.0-Universal.apk)** — универсальная версия Google Antigravity со встроенным LSE-транслятором (`libqemu.so`) и автоматической поддержкой всех процессоров (ARMv8.0 и ARMv8.1+).
* **[Antigravity-v2.11.0-Vanilla.apk](https://github.com/werdio325-png/antigravity-android/releases/download/v2.11.0/Antigravity-v2.11.0-Vanilla.apk)** — чистая оригинальная версия Google Antigravity для пользователей с чипами ARMv8.1+ и прямым доступом к API.
* **[antigravity-cores-arm64-v2.11.0.tar.gz](https://github.com/werdio325-png/antigravity-android/releases/download/v2.11.0/antigravity-cores-arm64-v2.11.0.tar.gz)** — сжатый архив всех нативных ARM64-ядер и библиотек для разработчиков, собирающих проект из исходников.

---

## Благодарности и первоисточники (Credits & Acknowledgments)

Выражаем огромную благодарность разработчику [**@wallentx**](https://github.com/wallentx) и его проекту [**antigravity-cli-termux**](https://github.com/wallentx/antigravity-cli-termux), а также пионерам сообщества [**@hjotha**](https://github.com/hjotha) и [**@Brajesh2022**](https://github.com/Brajesh2022)!

### Инженерная основа:
* **Патчинг адресного пространства VA39 (TCMalloc):** оригинальный бинарник Google Antigravity использует TCMalloc, рассчитывающий на 48-битное виртуальное адресное пространство (`VA48`). На ядрах Android пользовательское пространство ограничено 39 битами (`VA39`). Благодаря исследованиям сообщества и реализации в `antigravity-cli-termux`, инструкции `ubfx`, маски адресов и выравнивание `mmap` модифицируются в бинарнике для полной стабильности на мобильных чипсетах.
* **Трансляция системных вызовов SECCOMP:** низкоуровневые вызовы (например, `faccessat2`), блокируемые строгой политикой SECCOMP ядра Android, перенаправляются и обрабатываются без крашей.
* **Мост между Bionic libc и glibc:** нативный динамический компоновщик `ld-linux-aarch64.so.1` в связке с библиотекой эмуляции разделяемой памяти SysV SHM (`libandroid-shmem.so`) связывает glibc-рантайм Google Antigravity с системными библиотеками Android Bionic.

### Развитие в `antigravity-android`:
Наш проект совершает качественный переход от консольного терминала к **автономной мобильной экосистеме**:
* Консольный TUI перенесён в полноценное нативное Android-приложение (APK) со встроенным веб-интерфейсом (WebView) на локальном порту `38696`.
* Добавлена поддержка системных плавающих окон (Freeform Window Mode) для комфортной многозадачности на планшетах, складных устройствах и смартфонах.
* Реализована неубиваемая служба переднего плана (Foreground Service), предотвращающая выгрузку рантайма операционной системой Android при выключении экрана или переключении задач.
* Добавлен OAuth-мост `xdg-open` для автоматического открытия мобильного браузера при авторизации в аккаунте Google.

---

## Ключевые особенности

- **Полноценное Android-приложение (APK):** запуск с графическим WebView-интерфейсом без необходимости вручную открывать консоль Termux или настраивать chroot-контейнеры.
- **Плавающие окна (Freeform Window Mode):** работа в отдельном перемещаемом окне с изменяемым размером на планшетах, складных устройствах и смартфонах с поддержкой многооконности Android.
- **Фоновая служба (Foreground Service):** фоновый сервис гарантирует, что среда выполнения не будет выгружена системой при выключении экрана или переключении задач.
- **Универсальная архитектура ядер (Universal & Vanilla):**
  - **Universal (`libqemu.so` + `libserver.so`):** универсальное ядро с прозрачной трансляцией LSE atomics для старых процессоров (ARMv8.0) и прямым выполнением на ARMv8.1+.
  - **Vanilla (`libserver-vanilla.so`):** чистое оригинальное ядро Google Antigravity для пользователей с чипами ARMv8.1+ и прямым доступом к API.
- **Автономный Rootfs:** минимальный runtime-стек (glibc 2.44, Python 3, Git, cURL, jq, ripgrep, BusyBox), распаковывающийся во внутреннее хранилище приложения.
- **Интеграция с Shizuku и Root:** прямой доступ к системному шеллу Android (UID 2000 через `rish` или root через `su`) для управления пакетами, сервисами и файловой системой.
- **OAuth-мост:** перехват запросов авторизации Google через `xdg-open` с автоматическим открытием мобильного браузера.

---

## Структура проекта

```
antigravity-android/
├── LICENSE                     # Лицензия MIT
├── README.md                   # Главная документация и руководство
├── .gitignore                  # Исключения (токены, кэши, APK, ядра >100MB)
│
├── app/                        # Исходный код Android APK
│   ├── src/main/AndroidManifest.xml     # Манифест с флагами Freeform и сервиса
│   ├── src/main/java/com/antigravity/standalone/
│   │   ├── MainActivity.java   # Главная Activity, WebView, перехватчик URL
│   │   └── EngineService.java  # Неубиваемый Foreground Service
│   └── src/main/res/           # Разметка, иконки, конфигурация сети
│
├── assets/                     # Графические ресурсы
│   └── mascot.png              # Официальный маскот проекта
│
├── config/                     # Предустановленные профили и правила
│   ├── AGENTS.md               # Системные правила среды для ИИ-агентов
│   ├── settings.json           # Начальные настройки темы и моделей
│   ├── antigravity_state.pbtxt # Чистый профиль состояния онбординга
│   ├── jetski_state.pbtxt      # Профиль состояния движка Jetski
│   └── projects/               # Базовые профили рабочих пространств
│
├── core/                       # Нативные библиотеки связывания (ARM64)
│   ├── arm64-v8a/
│   │   ├── libldlinux.so       # Динамический компоновщик glibc (ld-linux-aarch64)
│   │   └── libandroid-shmem.so # Эмулятор SysV SHM
│   └── README.md               # Инструкция по размещению ядер libserver*.so
│
├── rootfs/                     # Минимальное runtime-окружение (упаковывается в APK)
│   ├── bin/                    # xdg-open, bash, rish, su, busybox, git, python3, curl, jq, rg
│   ├── etc/                    # resolv.conf, hosts, nsswitch.conf, ssl certs
│   └── lib/                    # glibc 2.44, nss, readline, python3 runtime libs
│
├── build.sh                    # Скрипт сборки APK (patched / vanilla / all)
├── install.sh                  # Скрипт установки APK через Shizuku
├── launch.sh                   # Скрипт запуска в окне (--window) или на полный экран (--full)
└── screenshot.py               # Инструмент захвата экрана плавающего окна
```

---

## Быстрый старт

### 1. Сборка APK из исходников
Для сборки запустите в окружении Termux (требуются пакеты `aapt`, `javac`, `d8`, `apksigner`):

```bash
# Сборка обеих версий (Patched и Vanilla):
bash build.sh all

# Или конкретной версии:
bash build.sh patched
bash build.sh vanilla
```

Собранные готовые APK помещаются в каталог `dist/`.

### 2. Установка через Shizuku
Установите пакет без подтверждений прямо из терминала:

```bash
# Установка версии с патчем авторизации (для работы под VPN):
bash install.sh patched

# Установка чистой версии (для тех, кому VPN не нужен по локации):
bash install.sh vanilla
```

### 3. Запуск

```bash
# Запуск в свободном плавающем окне (Freeform):
bash launch.sh --window

# Запуск на весь экран:
bash launch.sh --full

# Полная остановка приложения и фоновой службы:
bash launch.sh stop
```

---

## Планы по развитию (Roadmap)

- [ ] **Оптимизация холодного старта:** сокращение времени запуска и прогрева среды с текущих 4–6 секунд за счет предпрогрева сокета и кэширования runtime-окружения.
- [ ] **Нативный выбор медиафайлов (Add Context Media):** реализация `WebChromeClient.onShowFileChooser` для открытия системной шторки галереи/Google Photos поверх приложения при прикреплении изображений.
- [ ] **Исправление стабильности чат-сессии:** добавление `onRenderProcessGone` в WebView и автоматический перезапуск упавшего gRPC/ConnectRPC-стрима при выходе устройства из сна.
- [ ] **Надежное прерывание генерации (Stop/Cancel):** принудительный сброс зависших дочерних процессов шелла (`killpg`) и состояния исполнителя при нажатии кнопки остановки, исключающий ошибку `executor has not processed the previous input yet`.
- [ ] **Отображение кастомизаций в UI:** адаптация путей и API чтения навыков (Skills), правил и MCP-серверов для их корректного вывода в настройках веб-интерфейса на мобильном устройстве.

---

## Команда проекта

* **[werdio325-png](https://github.com/werdio325-png)** — Владелец и создатель проекта.
* **[maksimcvetkov888-crypto](https://github.com/maksimcvetkov888-crypto)** (*AngelV1bexs*) — Коллаборатор и мейнтейнер.

---

## Лицензия

Проект распространяется под открытой и свободной лицензией **MIT**. Подробности в файле [LICENSE](LICENSE).
Google Antigravity является торговой маркой Google LLC. Данный проект является независимой разработкой сообщества.
