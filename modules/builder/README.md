# Модуль сборщика APK: `modules/builder`

> Автономный конвейер сборки Android APK без Gradle и Android Studio.
> Оптимизирован для нативного выполнения непосредственно в окружении Android (ARM64 / Linux).

---

## 1. Обзор архитектуры

Сборка Antigravity APK выполняется полностью независимо от тяжелых систем сборки (Gradle, AGP, Groovy/Kotlin daemon). Оркестратор [`build_apk.sh`](build_apk.sh) вызывает цепочку атомарных скриптов, напрямую управляя низкоуровневыми бинарными утилитами Android SDK (`aapt`, `javac`, `d8`/`r8`, `zipalign`, `apksigner`) и специализированными Python-утилитами.

### Схема конвейера (Pipeline Diagram)

```mermaid
flowchart TD
    Env[config/app.env] --> P01[01_gen_config.py]
    Src[app/src/main/...] --> P01
    
    subgraph Config & Resources
        P01 -->|BuildConfig.java| GenDir[build/gen]
        P01 -->|gen.xml| BuildRes[build/res/values]
        P01 -->|Dev-aware Manifest| TargetManifest[build/AndroidManifest.xml]
        TargetManifest & ResDir[app/src/main/res] & BuildRes --> P02[02_r_java.sh: aapt overlay]
        P02 -->|R.java| GenDir
    end

    subgraph Bytecode & Dexing
        GenDir & Src --> JavaArg[@build/java_sources.txt]
        JavaArg --> P03[03_compile_java.sh: javac]
        P03 -->|*.class| ObjDir[build/obj]
        ObjDir --> ClassArg[@build/class_files.txt]
        ClassArg --> P04[04_dex.sh: D8 via r8.jar]
        P04 -->|classes.dex| DexDir[build/dex]
    end

    subgraph Assets & Packaging
        Runtime[modules/runner/prepare_runtime] --> P05[05_stage_assets.py]
        Web[web/] & EnvDist[env/dist] --> P05
        P05 -->|staged assets| StagingDir[build/staging/assets]
        
        TargetManifest & ResDir & BuildRes & StagingDir & DexDir --> P06[06_package.sh: aapt + zip]
        P06 -->|raw APK| Unaligned[build/unaligned.apk]
    end

    subgraph Align, Sign & Verify
        Unaligned --> P07[align.py: zipalign / python]
        P07 -->|4-byte aligned APK| Aligned[build/aligned.apk]
        Aligned --> P08[08_sign.sh: apksigner]
        P08 -->|Signed APK (v1+v2+v3)| Dist[dist/antigravity.apk / antigravity-dev.apk]
        Dist --> P09[09_verify.sh: apksigner verify]
    end
```

---

## 2. Этапы конвейера сборки (Build Stages)

| Этап | Скрипт | Главный инструмент | Входы | Выходы | Назначение |
|---|---|---|---|---|---|
| **01** | [`01_gen_config.py`](01_gen_config.py) | Python 3 | `config/app.env`, `AndroidManifest.xml` | `build/gen/**/BuildConfig.java`, `build/res/values/gen.xml`, `build/AndroidManifest.xml` | Генерация констант, портов и dev-манифеста в build-дерево (без загрязнения git) |
| **02** | [`02_r_java.sh`](02_r_java.sh) | `aapt` | `AndroidManifest.xml`, `res/`, `build/res/`, `android.jar` | `build/gen/**/R.java` | Компиляция идентификаторов ресурсов с оверлеем и пропуском дубля в релизе |
| **03** | [`03_compile_java.sh`](03_compile_java.sh) | `javac` | `@build/java_sources.txt`, `android.jar` | `build/obj/**/*.class` | Компиляция Java-исходников через аргументный файл (защита от `ARG_MAX`) |
| **04** | [`04_dex.sh`](04_dex.sh) | `D8` (`tools/r8.jar`) | `@build/class_files.txt`, `android.jar` | `build/dex/classes.dex` | Трансляция Java-байткода в DEX через аргументный файл D8 без переполнения CLI |
| **05** | [`05_stage_assets.py`](05_stage_assets.py) | Python 3 + `modules/runner` | Ядро рантайма, `web/`, `env/dist/env` | `build/staging/assets/` | Сборка и верификация ассетов (runtime, web, env) |
| **06** | [`06_package.sh`](06_package.sh) | `aapt` + `zipfile` | Ресурсы + оверлей `build/res`, манифест, `classes.dex`, `staging/assets/` | `build/unaligned.apk` | Создание базового APK с оверлеем и инъекция dex и ассетов |
| **07** | [`align.py`](align.py) | `zipalign` / Python | `build/unaligned.apk` | `build/aligned.apk` | 4-байтовое выравнивание ZIP-архива для `mmap` |
| **08** | [`08_sign.sh`](08_sign.sh) | `apksigner` | `build/aligned.apk`, `tools/debug.keystore` | `dist/antigravity.apk` или `dist/antigravity-dev.apk` | Подпись схемами v1, v2 и v3 |
| **09** | [`09_verify.sh`](09_verify.sh) | `apksigner` | `dist/*.apk` | Результат проверки в stdout | Полная проверка валидности и схемы подписи |

---

## 3. Детальное описание шагов

### Шаг 01: Генерация конфигурации (`01_gen_config.py`)
- **Вход**: Переменные из `config/app.env` и `DEV_MODE` (0 или 1).
- **Выход**:
  - `build/gen/com/agy/BuildConfig.java`: статические поля `PORT`, `GATEWAY_PORT`, `CORE_VERSION`, `APP_VERSION_NAME`, `DEBUG`.
  - `build/res/values/gen.xml`: строковые и целочисленные XML-ресурсы для Android framework (генерируются в дерево сборки, исключая загрязнение репозитория и `git status`).
  - `build/AndroidManifest.xml`: подстановка `${applicationId}`, а в dev-режиме — изменение пакета на `dev.continuum.antigravity.dev`, установка `android:debuggable="true"` и изоляция `permission.INTERNAL_BROADCAST`.

### Шаг 02: Генерация R.java (`02_r_java.sh`)
- Запускает `aapt package -m -J <gen_dir>` для разбора ресурсов и создания числовых ID с оверлеем `-S "$BUILD_DIR/res" -S "$RES_DIR" --auto-add-overlay`.
- Проверяет имя пакета в целевом манифесте: если пакет равен базовому `dev.continuum.antigravity`, второй дублирующий запуск `aapt` опускается. Если имя пакета отличается (например, `dev.continuum.antigravity.dev`), генерируется второй фоллбэк `dev.continuum.antigravity.R` через `--custom-package dev.continuum.antigravity` для совместимости со старыми вызовами.

### Шаг 03: Компиляция Java (`03_compile_java.sh`)
- Собирает список исходников приложения и сгенерированных классов (`BuildConfig`, `R`) в аргументный файл `@$BUILD_DIR/java_sources.txt`, полностью устраняя риск ошибки `ARG_MAX` (`Argument list too long`).
- Компилирует код компилятором `javac`: `-encoding UTF-8 -source 1.8 -target 1.8 -cp tools/android.jar @$BUILD_DIR/java_sources.txt`.

### Шаг 04: Дексирование D8 (`04_dex.sh`)
- Сканирует директорию классов `$OBJ_DIR` и генерирует аргументный файл `@$BUILD_DIR/class_files.txt`, устраняя ограничение на размер командной строки.
- Преобразует скомпилированные `.class` файлы в `classes.dex` через автономный D8 (`com.android.tools.r8.D8` из `tools/r8.jar`).
- Параметры: `--output $DEX_DIR`, `--lib tools/android.jar`, `--min-api 24`, `@$BUILD_DIR/class_files.txt`.

### Шаг 05: Стейджинг ассетов (`05_stage_assets.py`)
- Собирает три группы ресурсов в `build/staging/assets/`:
  1. `runtime/`: бинарное ядро `language_server` (пропатченное и криптографически проверенное через `prepare_runtime.py`).
  2. `web/`: фронтенд-интерфейс (`index.html`, `compiled_tailwind.css`, `generated/main.js` и т.д.). Перед копированием автоматически вызывает сборку бандлов `bash web/build.sh`, гарантируя актуальность веб-ресурсов из `web/src/`. Исключает служебные файлы (`main.js`, `src/`, `tests/`, `build.sh`, `patch_main.py`, а также `src.manifest`) через фильтр `web_ignore`.
  3. `env/`: оверлеи окружения, `libtermux-exec.so` и `manifest.json`.

### Шаг 06: Упаковка APK (`06_package.sh`)
- `aapt package -f` формирует каркас APK с поддержкой оверлея ресурсов (`-S "$BUILD_DIR/res" -S "$RES_DIR" --auto-add-overlay`), бинарным `AndroidManifest.xml` и скомпилированными ресурсами (`resources.arsc`).
- Потоковый Python ZIP-инжектор добавляет `classes.dex` и дерево `assets/`.
- Файлы, уже сжатые алгоритмами gzip/png/jpeg/zip, сохраняются без повторного сжатия (`ZIP_STORED`), остальные сжимаются через `ZIP_DEFLATED`.

### Шаг 07: Выравнивание ZipAlign (`align.py`)
- Выравнивает заголовки некомпилированных данных на границу 4 байт (и 4096 байт для библиотек `.so`), чтобы ОС Android могла обращаться к файлам напрямую через `mmap()` без распаковки в RAM.
- **Двухуровневая стратегия**:
  1. Системный бинарник `zipalign` (если установлен в `$PATH`).
  2. Встроенный кроссплатформенный чистый Python-алгоритм без внешних зависимостей.

### Шаги 08 и 09: Подпись и проверка (`08_sign.sh` & `09_verify.sh`)
- `apksigner sign` подписывает APK ключом из `tools/debug.keystore`.
- Поддерживаются схемы:
  - **v1**: JAR-сигнатура (совместимость).
  - **v2**: APK Signature Scheme v2 (Android 7.0+).
  - **v3**: APK Signature Scheme v3 (Android 9.0+).
- `apksigner verify --verbose` производит верификацию сигнатуры и дайджестов манифеста.

---

## 4. Зависимости и инструменты

| Инструмент | Минимальная версия | Назначение | Расположение |
|---|---|---|---|
| **Python** | 3.8+ | Конфигурация, стейджинг, упаковка, ZipAlign | Системный `$PATH` |
| **aapt** | v0.2+ (v1) | Парсинг XML, компиляция ресурсов, каркас APK | Системный `$PATH` |
| **javac** | OpenJDK 8 / 11 / 17 | Компиляция `.java` -> `.class` | Системный `$PATH` |
| **java** | JRE 8+ | Запуск R8/D8 | Системный `$PATH` |
| **r8.jar** | Android R8/D8 | Дексер (bytecode -> DEX) | `tools/r8.jar` |
| **android.jar** | API 24+ (актуально API 28) | Android Framework Stubs | `tools/android.jar` |
| **zipalign** | Любая (опционально) | Быстрое нативное выравнивание | Системный `$PATH` |
| **apksigner** | 0.9+ | Подпись и проверка APK | Системный `$PATH` |
| **debug.keystore**| JKS/PKCS12 | Тестовый сертификат подписи | `tools/debug.keystore` |

---

## 5. Использование

```bash
# Стандартная сборка релизного APK (dist/antigravity.apk, порт 45157):
bash modules/builder/build_apk.sh

# Сборка релизной версии с обходом проверок (dist/antigravity-region-bypass.apk, порт 45157):
bash modules/builder/build_apk.sh --region-bypass

# Запуск тестов модуля builder:
bash modules/builder/tests/run_all.sh
```

---

## 6. Результаты аудита: баги, костыли и оптимизации

### 1. Критический баг в `align.py` (исправлен)
- **Проблема**: В чистой Python-реализации `align_with_python` для каждого элемента вызывался метод `zin.read()`. Этот метод распаковывает сжатые потоки `ZIP_DEFLATED`. Затем эти распакованные байты записывались в выходной файл с исходными заголовками `compress_type=8` и размером `compress_size`. В результате при отсутствии бинарника `zipalign` любой собранный APK был полностью поврежден (`zlib.error: invalid stored block lengths`).
- **Исправление**: Метод переписан на прямое чтение сырого потока сжатых байт (`raw payload`) по смещению локального заголовка с буферизацией чанками по 64 КБ. Повреждение данных устранено, потребление RAM снижено с O(N) до O(1).

### 2. Загрязнение исходного кода (`gen.xml`) (исправлено)
- **Проблема**: `01_gen_config.py` записывал `gen.xml` напрямую в каталог репозитория `app/src/main/res/values/gen.xml`, что приводило к загрязнению `git status` и риску случайного коммита dev-конфигурации.
- **Исправление**: Перенос генерации в дерево сборки `$BUILD_DIR/res/values/gen.xml`. В `02_r_java.sh` и `06_package.sh` добавлена поддержка ресурсного оверлея `-S "$BUILD_DIR/res" -S "$RES_DIR" --auto-add-overlay`, благодаря чему сгенерированные значения подставляются динамически, а рабочее дерево Git остается чистым.

### 3. Двойной прогон `aapt` в `02_r_java.sh` (исправлено)
- **Проблема**: В релизном режиме `aapt` запускался дважды с идентичными параметрами (`dev.continuum.antigravity`), создавая избыточную нагрузку на CPU.
- **Исправление**: В `02_r_java.sh` добавлена проверка манифеста (`PKG=$(sed -n -E 's/.*package="([^"]+)".*/\1/p' "$MANIFEST" | head -n 1)`). Если целевой пакет уже равен `dev.continuum.antigravity`, повторный запуск пропускается. Если пакет отличается (например, `dev.continuum.antigravity.dev`), выполняется генерация обоих пакетов (`dev.continuum.antigravity.dev.R` и совместимого фоллбэка `dev.continuum.antigravity.R`).

### 4. Риск переполнения командной строки (`ARG_MAX`) (исправлено)
- **Проблема**: В `03_compile_java.sh` и `04_dex.sh` все исходные файлы и классы подставлялись массивами bash `${JAVA_SOURCES[@]}` и `${CLASS_FILES[@]}`. При масштабировании кодовой базы это вызывало системную ошибку ядра Linux `Argument list too long` (`E2BIG`).
- **Исправление**:
  - Для `javac` в `03_compile_java.sh`: сформирован аргументный файл `@$BUILD_DIR/java_sources.txt`, в который записываются все пути с экранированием.
  - Для `D8` в `04_dex.sh`: сканируется `$OBJ_DIR` и генерируется аргументный файл `@$BUILD_DIR/class_files.txt`, передаваемый в `com.android.tools.r8.D8` (устраняя ограничение длины команды и обходя несовместимость D8 с передачей сырых каталогов).

### 5. Множественные запуски JVM (JVM Overhead)
- **Проблема**: Конвейер запускает виртуальную машину Java 4 раза подряд (`javac`, `d8`, `apksigner sign`, `apksigner verify`). На мобильном CPU каждый запуск JVM занимает 0.8–1.5 секунды.
- **Решение**: Объединение фаз подписи и верификации, использование объединенных скриптов или демона компиляции.

### 6. Неработающий флаг `--fast`
- **Проблема**: Флаг `--fast` парсился в `build_apk.sh`, но не передавался в дочерние скрипты. При каждом запуске директория `build/` полностью удалялась (`rm -rf`), повторно копируя и перепаковывая ~240 МБ ассетов.
- **Решение**: Проброс флага `FAST_BUILD` в `05_stage_assets.py` и `06_package.sh` для инкрементальной сборки и пропуска неизменившихся ассетов ядра.

### 7. Избыточный дисковый I/O на флеш-памяти
- **Проблема**: Полная запись APK на диск выполняется 3 раза подряд: `unaligned.apk` (~103 МБ) -> `aligned.apk` (~103 МБ) -> `dist/antigravity.apk` (~103 МБ). Суммарная запись >300 МБ изнашивает flash-память Android-устройства.
- **Решение**: Выполнять `align.py` сразу в целевой файл `$DIST_DIR/antigravity.apk` и подписывать его на месте (`in-place signing`).

### 8. Интеграция веб-сборщика и предотвращение утечки манифестов (исправлено)
- **Проблема**: В `05_stage_assets.py` веб-ассеты копировались «как есть» из `web/` без предварительного запуска сборки, из-за чего изменения в `web/src/` могли не попадать в APK. Кроме того, в фильтре `web_ignore` отсутствовал `src.manifest`, в результате чего манифест исходников попадал в ресурсы APK.
- **Исправление**: В `05_stage_assets.py` внедрен автоматический запуск `bash web/build.sh` перед стадированием ассетов, а файл `src.manifest` добавлен в список игнорирования `web_ignore`.
