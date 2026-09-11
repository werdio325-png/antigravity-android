<p align="center">
  <img src="assets/mascot.png" width="140" height="140" alt="Antigravity Android Mascot" style="border-radius: 28px;">
</p>

<h1 align="center">Google Antigravity Standalone for Android</h1>

<p align="center">
  <b>Автономный ARM64-клиент для Google Antigravity с поддержкой Freeform-окон, фонового сервиса и независимой среды выполнения</b>
</p>

<p align="center">
  <a href="LICENSE"><img src="https://img.shields.io/badge/License-MIT-yellow.svg" alt="License: MIT"></a>
  <img src="https://img.shields.io/badge/Arch-ARM64--v8a-blue.svg" alt="ARM64">
  <img src="https://img.shields.io/badge/Platform-Android%208.0%2B-green.svg" alt="Android 8.0+">
  <img src="https://img.shields.io/badge/Engine-2.11.0-orange.svg" alt="Engine 2.11.0">
  <img src="https://img.shields.io/badge/Status-Active%20Development-brightgreen.svg" alt="Active Development">
</p>

---

## 🚀 Статус проекта: Активная разработка (Active Development)

> [!NOTE]
> **Проект находится в стадии активной разработки и постоянного совершенствования!**  
> Мы регулярно выпускаем обновления, оптимизируем энергопотребление, добавляем новые возможности интеграции с Android и актуализируем нативные ядра. Все идеи, баг-репорты и pull request'ы горячо приветствуются!

---

## 🙏 Благодарности и первоисточники (Credits & Acknowledgments)

Выражаем огромную благодарность разработчику [**@wallentx**](https://github.com/wallentx) и его проекту [**antigravity-cli-termux**](https://github.com/wallentx/antigravity-cli-termux)!

* Именно в **antigravity-cli-termux** была заложена фундаментальная основа: проведен глубокий реверс-инжиниринг и первичная адаптация нативного ARM64-ядра Google Antigravity под Android Bionic / glibc окружение, решены проблемы с SECCOMP-фильтрами (`faccessat2`), внедрен динамический компоновщик `ld-linux-aarch64` и эмуляция разделяемой памяти SysV SHM (`libandroid-shmem`).
* Проект **antigravity-android** развивает эту инициативу дальше: мы перенесли консольное ядро в **полноценное самостоятельное Android-приложение (APK)**, добавили графический WebView-интерфейс, интеграцию с системным оконным менеджером Android (Freeform), службу переднего плана (Foreground Service) для предотвращения выгрузки ОС Android, механизм автоматической авторизации через мобильный браузер и сборщик двойного ядра (*Patched / Vanilla*).

---

## ✨ Ключевые особенности

- 📱 **Полноценное Android-приложение (APK):** Больше не нужно вручную открывать консоль Termux или настраивать chroot-контейнеры.
- 🪟 **Плавающие окна (Freeform Window Mode):** Работает как отдельное перемещаемое окно с изменяемым размером на планшетах, складных экранах и смартфонах с поддержкой свободного оконного режима Android.
- ⚡ **Foreground Service (Фоновая служба):** Встроенный Android Service гарантирует, что Antigravity продолжит выполнение длительных задач и сборку проектов, даже если экран заблокирован или вы переключились на другое приложение.
- 🔄 **Двойная архитектура ядер (Dual-Core):**
  - **Patched (`libserver-patched.so`):** Байт-патч региональной верификации (`MANAGER_GATE_ARM64`) от Open AG Patcher для работы без VPN.
  - **Vanilla (`libserver-vanilla.so`):** Чистое оригинальное ядро для тех, кто предпочитает выходить через надежный VPN.
- 🧰 **Встроенный автономный Rootfs:** Минимальный Linux-стек (glibc 2.44, Python 3, Git, cURL, jq, ripgrep, BusyBox), автоматически распаковывающийся во внутреннее хранилище приложения.
- 🌐 **Интеграция с Shizuku / Root:** Полный доступ агента к системному шеллу Android (UID 2000 через `rish` или root через `su`) для управления пакетами (`pm`), окнами (`am`), экраном и файловой системой.
- 🔗 **Автоматический OAuth-мост:** Мост `xdg-open` перехватывает запросы входа Google и мгновенно открывает установленный браузер Android для быстрой авторизации.

---

## 📂 Структура проекта

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

## 🛠 Быстрый старт

### 1. Сборка APK
Для сборки из исходников запустите в окружении Termux (требуются `aapt`, `javac`, `d8`, `apksigner`):

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
# Установка версии с обходом региона:
bash install.sh patched

# Установка чистой версии (для VPN):
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

## 🗺 Планы по развитию (Roadmap)

- [ ] Встроенное автообновление ядер и компонентов прямо из графического интерфейса.
- [ ] Менеджер плагинов, навыков (Skills) и MCP-серверов в настройках приложения.
- [ ] Визуальный переключатель тем (Dark/Light/AMOLED) и активных моделей Gemini.
- [ ] Дополнительная оптимизация сна и фонового потребления аккумулятора.
- [ ] Интегрированная вкладка терминала для быстрого доступа к системному шеллу рядом с агентом.

---

## 👥 Команда проекта

* **[werdio325-png](https://github.com/werdio325-png)** — Владелец и создатель проекта.
* **[maksimcvetkov888-crypto](https://github.com/maksimcvetkov888-crypto)** (*AngelV1bexs*) — Коллаборатор и мейнтейнер.

---

## 📄 Лицензия

Проект распространяется под открытой и свободной лицензией **MIT**. Подробности в файле [LICENSE](LICENSE).
Google Antigravity является торговой маркой Google LLC. Данный проект является независимой разработкой сообщества.
