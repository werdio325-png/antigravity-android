# Antigravity v2 - Environment, Diagnostics & System Gateway Subsystem (`modules/env`)

> **Модуль `env`** — фундаментальная подсистема управления системным окружением, динамического разрешения путей Android/Linux, аппаратной и сетевой диагностики (preflight/port checker), структурированного телеметрического логирования, аудита выполнения команд и межпроцессного взаимодействия с Android System Gateway для Google Antigravity v2.

---

## 1. Архитектурный обзор

Модуль `modules/env` устраняет зависимость компонентов рантайма от фиксированных путей файловой системы Android и обеспечивает стандартизированную среду выполнения:

```
 +-----------------------------------------------------------------------------------+
 |                             SPECIFICATION LAYER                                   |
 |          [spec/paths.json]                     [spec/env_vars.json]               |
 |    (Базовые шаблоны путей Android)        (Static vars, PATH, LD_LIBRARY_PATH)    |
 +--------------------------+---------------------------------+----------------------+
                            |                                 |
                            v                                 v
 +-----------------------------------------------------------------------------------+
 |                              CONTEXT & RESOLVER                                   |
 |         [path_resolver.py]                          [env_builder.py]              |
 |   Динамический расчет package_id,         Сборка процесса: HOME, PREFIX, PATH,    |
 |   app_root, runtime, prefix, cache        TMPDIR, GODEBUG, GIT_CONFIG_NOSYSTEM    |
 +--------------------------+---------------------------------+----------------------+
                            |                                 |
       +--------------------+----------------+----------------+----------------+
       |                                     |                                 |
       v                                     v                                 v
+--------------------------+  +--------------------------+  +--------------------------+
|  DIAGNOSTICS & PREFLIGHT |  |   LOGGING & CRASH DUMP   |  |     COMMAND RUNNER       |
|      [preflight.py]      |  |         [log.py]         |  |       [runner.py]        |
|  - Архитектура (ARM64)   |  |  - ANSI Logger / JSONL   |  |  - Исполнение $ cmd      |
|  - Свободное место (>250)|  |   [flight_recorder.py]   |  |  - Метрики: duration_ms  |
|  - Права записи W_OK     |  |  - Аварийный дамп памяти |  |  - Аудит commands.jsonl  |
|    [port_checker.py]     |  |    и состояния диска     |  |  - Fallback & list       |
|  - Тест TCP Loopback     |  +--------------------------+  +--------------------------+
+--------------------------+                 |                                 |
                                             v                                 v
 +-----------------------------------------------------------------------------------+
 |                             IPC & SYSTEM GATEWAY                                  |
 |           [bridge/client.py]                           [bridge/commands.py]       |
 |    Авто-определение порта gateway.port           Высокоуровневые типизированные   |
 |    Транспорт: POST http://127.0.0.1:port/gateway  команды: notify, open, wakelock  |
 +-----------------------------------------------------------------------------------+
                                             |
                                             v
 +-----------------------------------------------------------------------------------+
 |                                CLI UTILITIES                                      |
 |          [cli/agy_system.py]                           [cli/agy_logs.py]          |
 |    Управление Android-уведомлениями,             Инспектор логов ядра core.log,   |
 |    Wakelock, Intent URL, Clipboard               дампов сбоев и commands.jsonl    |
 +-----------------------------------------------------------------------------------+
```

---

## 2. Структура каталогов модуля

```
modules/env/
├── spec/
│   ├── paths.json               # JSON-спецификация файловой структуры приложения
│   └── env_vars.json            # JSON-спецификация базовых переменных окружения
├── context/
│   ├── path_resolver.py         # Резолвер путей без хардкода, детектор package_id
│   └── env_builder.py           # Конструктор изолированного окружения процессов
├── diagnostics/
│   ├── preflight.py             # Префлайт-чекер архитектуры (ARM64), прав и памяти
│   └── port_checker.py          # Проверка доступности локальных TCP-портов
├── logging/
│   ├── log.py                   # Легковесный цветной ANSI + JSONL логгер
│   └── flight_recorder.py       # Генератор аварийных JSON-снимков (crash dump)
├── bridge/
│   ├── client.py                # IPC-клиент к Android Gateway с авто-fallback
│   └── commands.py              # Типизированные системные вызовы к Android OS
├── cmd/
│   └── runner.py                # Телеметрический движок выполнения команд
├── cli/
│   ├── agy_system.py            # CLI-контроллер Android Gateway (notify, open, wakelock)
│   └── agy_logs.py              # CLI-инспектор логов, истории команд и сбоев
└── tests/
    ├── run_all.sh               # Главный скрипт синтаксической и модульной проверки
    ├── test_context.py          # Тесты резолвера путей и сборки env
    ├── test_diagnostics.py      # Тесты префлайта и чекера портов
    ├── test_logging.py          # Тесты логгера и flight recorder
    ├── test_bridge.py           # Тесты клиента шлюза и типизации команд
    └── test_cli.py              # Тесты CLI-утилит agy_system и agy_logs
```

---

## 3. Спецификация окружения и путей (`spec/`)

### 3.1. Карта файловой системы (`spec/paths.json`)
Шаблоны путей используют плейсхолдер `{package_name}` (или `{app_id}`), динамически подменяемый в рантайме:
```json
{
  "app_root": "/data/user/0/{package_name}/files",
  "prefix": "/data/user/0/{package_name}/files/usr",
  "runtime": "/data/user/0/{package_name}/files/runtime",
  "cache": "/data/user/0/{package_name}/cache",
  "gemini_home": "/data/user/0/{package_name}/files/.gemini",
  "shared_storage": "/storage/emulated/0",
  "default_projects": "/storage/emulated/0/Documents"
}
```

### 3.2. Спецификация переменных окружения (`spec/env_vars.json`)
Определяет базовые переменные, порядок поиска в `PATH` и библиотеки:
```json
{
  "static": {
    "LANG": "C.UTF-8",
    "LC_ALL": "C.UTF-8",
    "GODEBUG": "netdns=go",
    "ANTIGRAVITY_VSCODE_HOST": "1"
  },
  "relative_to_app": {
    "HOME": ".",
    "TMPDIR": "../cache"
  },
  "path_order": [
    "{runtime}/bin",
    "{prefix}/bin",
    "/system/bin"
  ],
  "ld_library_paths": [
    "{runtime}/lib",
    "{prefix}/lib"
  ]
}
```

---

## 4. Контекст и динамический резолвер путей (`context/`)

### 4.1. `path_resolver.py`
Отвечает за устранение жестких привязок к имени пакета (`dev.continuum.antigravity` vs `dev.continuum.antigravity.dev` vs кастомные сборки):
- `get_application_id() -> str`: Определение идентификатора пакета по приоритетам:
  1. Переменная окружения `AGY_APPLICATION_ID`
  2. Переменная окружения `APPLICATION_ID`
  3. Флаг `DEV_MODE == "1"` (возвращает `dev.continuum.antigravity.dev`)
  4. По умолчанию: `dev.continuum.antigravity`
- `load_paths(custom_spec=None, app_id=None) -> Dict[str, str]`: Загрузка и интерполяция путей из `paths.json` с гарантированным получением абсолютных путей через `os.path.abspath(os.path.expanduser(...))`.
- `resolve_subpath(key: str, rel_path: str, custom_spec=None, app_id=None) -> str`: Безопасное присоединение подпутей. Для предотвращения сброса корневого каталога при передаче ведущего слэша (`/bin/tool`) вызывается `rel_path.lstrip("/")`.

### 4.2. `env_builder.py`
- `build_process_env(custom_spec_dir=None, extra_env=None, app_id=None) -> Dict[str, str]`:
  Формирует чистый и детерминированный словарь окружения для порождаемых процессов:
  - `HOME`: Привязан к `paths["app_root"]`
  - `PREFIX`: Linux-префикс `paths["prefix"]` (`/data/user/0/{package}/files/usr`)
  - `TMPDIR`: Каталог кэша `paths["cache"]`
  - `GIT_CONFIG_NOSYSTEM`: Значение `"1"` (блокирует ошибки отсутствия системного etc Termux)
  - `AGY_APPLICATION_ID`: Идентификатор активного пакета
  - `PATH`: Собран из `{runtime}/bin:{prefix}/bin:/system/bin`
  - `LD_LIBRARY_PATH`: Каталоги `{runtime}/lib` и `{prefix}/lib` (если директории существуют)
  - `extra_env`: Дополнительные пользовательские переменные

---

## 5. Системная диагностика (`diagnostics/`)

### 5.1. Префлайт-чекер (`diagnostics/preflight.py`)
Проверяет готовность хоста Android перед развертыванием или запуском ядра:
```python
def run_preflight(app_root: Optional[str] = None, min_mb_free: int = 250) -> Dict[str, Any]
```
- **Архитектура процессора**: Проверяет `platform.machine()`. Допускаются только `aarch64` / `arm64`.
- **Свободное дисковое пространство**: Проверяет доступную память на разделе `app_root` через `shutil.disk_usage()`. Порог по умолчанию — не менее 250 МБ.
- **Права на запись**: Проверяет `os.access(app_root, os.W_OK)`.
- **Динамический fallback**: Если `app_root` не задан, резолвит его через `path_resolver`, а затем через `$HOME`.

### 5.2. Валидатор портов (`diagnostics/port_checker.py`)
```python
def check_port_available(port: int, host: str = "127.0.0.1") -> Dict[str, Any]
```
- Проверяет возможность привязки TCP-сокета (`socket.bind`) к loopback-интерфейсу с таймаутом 0.5с.
- Возвращает `{"port": port, "available": True/False, "error": None/str}`.

---

## 6. Логирование и постмортем аварий (`logging/`)

### 6.1. Структурированный логгер (`logging/log.py`)
```python
def log(level: str, tag: str, message: str, meta: Dict[str, Any] = None, jsonl_file: str = None) -> None
```
- **ANSI Цвета**: `DEBUG` (серый), `INFO` (синий), `OK` (зеленый), `WARN` (желтый), `ERROR` (красный).
- **Поддержка `NO_COLOR`**: При установке переменной окружения `NO_COLOR` или `TERM=dumb` экранирующие символы автоматически отключаются.
- **Разделение потоков**: Логи уровня `ERROR` выводятся в `sys.stderr`, остальные уровни — в `sys.stdout`.
- **JSONL Streaming**: При указании `jsonl_file` запись формируется в атомарном формате JSON Lines с меткой времени в миллисекундах (`ts`). Операции защищены блоком `try-except`.

### 6.2. Аварийный регистратор (`logging/flight_recorder.py`)
```python
def record_crash_snapshot(error_msg: str, dump_path: str, extra_context: Dict[str, Any] = None) -> str
```
- Создает детальный постмортем снимок инцидента:
  - Точное время (epoch ms и ISO 8601 UTC)
  - Сообщение об ошибке и контекст (`extra_context`)
  - Данные платформы, архитектура, версия Python, PID
  - Состояние файловой системы (`shutil.disk_usage`) целевого каталога
- Сохраняет форматированный JSON по пути `dump_path`.

---

## 7. Системный шлюз и IPC-мост (`bridge/`)

Обеспечивает межпроцессную связь между Python-скриптами, фоновыми задачами и системными службами Android OS через `CoreServerService` (Android System Gateway).

### 7.1. IPC-клиент (`bridge/client.py`)
- `resolve_gateway_url() -> str`:
  1. Проверяет переменную окружения `AGY_GATEWAY_URL`.
  2. Ищет файл динамического порта `gateway.port` в `$HOME/logs/gateway.port`, `app_root/logs/gateway.port` и стандартных директориях `dev.continuum.antigravity.dev` / `dev.continuum.antigravity`.
  3. При отсутствии файла выбирает порт по умолчанию: `45161` для `DEV_MODE=1` / `dev.continuum.antigravity.dev`, либо `45160` для production.
- `send_command(command: str, payload: dict = None, gateway_url: str = None) -> dict`:
  - Отправляет HTTP POST запрос с JSON-конвертом `{command, payload}` на эндпоинт `/gateway`.
  - Автоматический failover: при недоступности основного порта выполняет попытку переподключения на альтернативный порт (`45160` <-> `45161`).
  - Возвращает `{ok: False, fallback: True, error: ...}` при невозможности связаться со шлюзом без генерации необработанных исключений.

### 7.2. Типизированный фасад команд (`bridge/commands.py`)
- `notify(title, message, progress=None, tag="agy_status", channel="agy_tasks", ongoing=False) -> dict`: Отправка Android-уведомления в статус-бар с поддержкой прогресс-бара (0-100) и закрепления.
- `cancel_notify(tag="agy_status", notification_id=1001) -> dict`: Отзыв активного уведомления.
- `open_url(url: str) -> dict`: Открытие веб-ссылки или диплинка через Android Intent (`ACTION_VIEW`).
- `wakelock(acquire=True, tag="agy_task") -> dict`: Удержание/освобождение процессорного `PARTIAL_WAKE_LOCK`.
- `clipboard_set(text: str) -> dict`: Копирование текста в системный буфер обмена Android.
- `clipboard_get() -> dict`: Чтение текста из системного буфера обмена Android.
- `system_info() -> dict`: Запрос состояния батареи, сети, свободной памяти и диска.

---

## 8. Движок аудита и выполнения команд (`cmd/runner.py`)

Утилита и библиотека для прозрачного исполнения команд оболочки с расширенным профилированием и аудитом:

```bash
# Исполнение команды с телеметрией:
python3 modules/env/cmd/runner.py run "echo 'Hello Antigravity'"

# Прямой вызов:
python3 modules/env/cmd/runner.py pkg list

# Просмотр последних выполненных команд:
python3 modules/env/cmd/runner.py list 20
```

- Вычисляет точную продолжительность выполнения в миллисекундах (`duration_ms`).
- Записывает каждую команду в append-only аудит-лог `commands.jsonl` в директории логов приложения (`$HOME/logs/commands.jsonl`).
- Выводит форматированное дерево с кодом возврата, длительностью и первыми строками вывода.

---

## 9. CLI интерфейсы (`cli/`)

### 9.1. Системный контроллер (`cli/agy_system.py`)

Единый терминальный интерфейс для управления Android-функциями:

```bash
# 1. Системные уведомления:
python3 modules/env/cli/agy_system.py notify -t "Сборка завершена" -m "APK успешно скомпилирован" --tag "build"
python3 modules/env/cli/agy_system.py notify -t "Загрузка" --progress 75 --ongoing
python3 modules/env/cli/agy_system.py notify cancel --tag "build"

# 2. Открытие URL в системном браузере / Custom Tabs:
python3 modules/env/cli/agy_system.py open "https://github.com"

# 3. Управление фоновым режимом (Wakelock):
python3 modules/env/cli/agy_system.py wakelock on --tag "long_job"
python3 modules/env/cli/agy_system.py wakelock off --tag "long_job"

# 4. Диагностика хоста Android:
python3 modules/env/cli/agy_system.py info
python3 modules/env/cli/agy_system.py --json info

# 5. Буфер обмена:
echo "секретный токен" | python3 modules/env/cli/agy_system.py clipboard set
python3 modules/env/cli/agy_system.py clipboard get
```

### 9.2. Инспектор телеметрии и логов (`cli/agy_logs.py`)

Инструмент анализа состояния рантайма ядра Antigravity:

```bash
# 1. Просмотр последних строк общего лога:
python3 modules/env/cli/agy_logs.py -n 50

# 2. Фильтрация по подсистеме и уровню важности:
python3 modules/env/cli/agy_logs.py --channel core --level ERROR

# 3. Аудит выполненных команд:
python3 modules/env/cli/agy_logs.py --commands -n 25

# 4. Детальный разбор конкретной команды (STDOUT, STDERR, время):
python3 modules/env/cli/agy_logs.py --cmd-id "cmd_1792097231_1ec8"

# 5. Проверка последнего отчета об аварии (Crash Report):
python3 modules/env/cli/agy_logs.py --crash
python3 modules/env/cli/agy_logs.py --crash --json
```

---

## 10. Модульное тестирование (`tests/`)

Тестовый набор покрывает 100% компонентов подсистемы `env`.

### Запуск тестов
```bash
bash modules/env/tests/run_all.sh
```

### Состав тестового набора
| Тестовый файл | Компонент | Проверяемый сценарий |
| :--- | :--- | :--- |
| `test_context.py` | `path_resolver`, `env_builder` | 9 тестов: резолв путей, subpath с ведущим слэшем, переменные `HOME`, `PREFIX`, `TMPDIR`, `GIT_CONFIG_NOSYSTEM`, динамический `AGY_APPLICATION_ID`, `DEV_MODE` и переопределение `app_id`. |
| `test_diagnostics.py` | `preflight`, `port_checker` | 3 теста: проверка текущей ФС, дефолтный префлайт без аргументов, валидация свободных TCP loopback портов. |
| `test_logging.py` | `log`, `flight_recorder` | 2 теста: запись структурированного JSONL потока, генерация снимка аварии со сбором метаданных платформы и диска. |
| `test_bridge.py` | `client`, `commands` | 5 тестов: авто-fallback шлюза в оффлайн-режиме, форматирование URL, wakelock, интроспекция `typing.get_type_hints()` и валидация всех методов команд. |
| `test_cli.py` | `agy_system`, `agy_logs` | 6 тестов: вызов справки CLI, парсинг системной информации в JSON, проверка отчетов о сбоях, чтение буфера в JSON, аудит команд через `--commands`. |

---

## 11. Аудит надёжности, выявленные баги и исправления

В ходе глубокого аудита исходного кода подсистемы `modules/env` были обнаружены и устранены критические архитектурные дефекты, ошибки путей в Android и проблемы типизации:

### 11.1. Критический хардкод путей в `preflight.py`
- **Проблема**: В сигнатуре функции `run_preflight` аргумент по умолчанию был жестко зафиксирован: `app_root: str = "/data/user/0/dev.continuum.antigravity/files"`.
- **Следствие**: В дебаг-сборках (`dev.continuum.antigravity.dev`) или любых пользовательских пакетах вызов `run_preflight()` без аргументов проверял несуществующий каталог production-пакета `dev.continuum.antigravity`. В результате `os.path.exists()` возвращал `False`, `free_mb = 0`, и префлайт-чекер безусловно рапортовал об ошибке `ok: False`, блокируя запуск ядра.
- **Исправление**: Параметр изменен на `app_root: Optional[str] = None`. Добавлено каскадное динамическое определение каталога через `path_resolver.load_paths()["app_root"]` -> `$HOME` -> fallback.

### 11.2. Ошибка типизации `NameError: Optional` в `bridge/commands.py`
- **Проблема**: В сигнатурах функций использовались аннотации типов `Optional[int]`, `Dict[str, Any]`, однако импорт из модуля `typing` отсутствовал.
- **Следствие**: При интроспекции типов (например, через `typing.get_type_hints(commands.notify)` или библиотеки валидации Pydantic/FastAPI) в Python 3.10+ возбуждалось исключение `NameError: name 'Optional' is not defined`.
- **Исправление**: Добавлен явный импорт `from typing import Dict, Any, Optional`, а также реализованы недостающие методы `clipboard_get()`, `cancel_notify()` и `system_info()`.

### 11.3. Подавление логирования и хардкод в `cmd/runner.py`
- **Проблема**: Путь `LOG_DIR` был жестко задан как `/data/user/0/dev.continuum.antigravity/files/logs`.
- **Следствие**: При работе в окружении `dev.continuum.antigravity.dev` создание директории или запись в `commands.jsonl` завершалась отказом в доступе (`PermissionDenied`), который молча глушился блоком `except Exception: pass`. Все команды, запущенные через `runner.py`, теряли историю аудита.
- **Исправление**: Реализована функция `_resolve_log_dir()`, приоритетно использующая `$HOME/logs` и `path_resolver`.

### 11.4. Недоступность логов и дампов сбоев в `cli/agy_logs.py`
- **Проблема**: Пути к файлам `commands.jsonl`, `logs/core.log` и каталогу `logs/crashes` были жестко привязаны к `dev.continuum.antigravity`. При этом в `read_file_fallback` проверялся путь `~/.gemini/logs/core.log`, тогда как реальный файл создается в `$HOME/logs/core.log`.
- **Следствие**: Если шлюз Android Gateway был оффлайн, команда `agy_logs.py` не могла обнаружить ни один лог-файл и выводила пустоту.
- **Исправление**: Внедрена функция `_find_commands_log()`, пути объединены с динамическим резолвером, а в список кандидатов `core.log` добавлен `$HOME/logs/core.log`.

### 11.5. Дублирование логики поиска порта Gateway
- **Проблема**: Функция `resolve_gateway_url()` в `bridge/client.py` и `get_gateway_base()` в `cli/agy_logs.py` содержали идентичный дублированный код, причем обе функции проверяли только статические пути к `gateway.port`.
- **Исправление**: `agy_logs.py` теперь импортирует централизованный метод из `bridge.client`, а сам резолвер порта проверяет `$HOME/logs/gateway.port` и флаг окружения `AGY_APPLICATION_ID == "dev.continuum.antigravity.dev"`.

### 11.6. Отсутствие `$PREFIX` и `$GIT_CONFIG_NOSYSTEM` в `env_builder.py`
- **Проблема**: Переменная `PREFIX` (`/data/user/0/{package}/files/usr`) является ключевой переменной окружения в Android userland (Termux / AGY). Несмотря на наличие ключа `"prefix"` в `paths.json`, `env_builder.py` не устанавливал `PREFIX`. Также отсутствовал флаг `GIT_CONFIG_NOSYSTEM=1`.
- **Исправление**: Добавлена явная установка `env["PREFIX"] = paths["prefix"]` и `env["GIT_CONFIG_NOSYSTEM"] = "1"`.

### 11.7. Баг сброса пути в `path_resolver.resolve_subpath`
- **Проблема**: В стандартной библиотеке Python вызов `os.path.join("/a/b", "/c")` возвращает `"/c"`. Передача относительного пути с ведущим слэшем (например, `resolve_subpath("runtime", "/bin/sh")`) приводила к отбрасыванию базового каталога.
- **Исправление**: Добавлена нормализация `rel_path.lstrip("/")`.

### 11.8. Игнорирование флага `--json` и тихие ошибки в `cli/agy_system.py`
- **Проблема**: Подкоманда `clipboard` игнорировала аргумент `--json` и ничего не сообщала пользователю при ошибках связи со шлюзом.
- **Исправление**: Реализован вывод сырого JSON при флаге `--json` и вывод сообщений об ошибках в `stderr` с ненулевым кодом выхода.
