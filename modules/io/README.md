# Antigravity v2 - I/O & Event Bus Subsystem (`modules/io`)

> **Модуль I/O** — центральная подсистема межпроцессного взаимодействия (IPC), перехвата системных потоков (stdout/stderr) и асинхронной маршрутизации событий в среде Android/Linux для Google Antigravity v2.

---

## 1. Архитектурный обзор

Модуль `modules/io` построен по архитектурному паттерну **Publish/Subscribe Event Bus** с поддержкой потоковых перехватчиков (Stream Interceptors) и гетерогенных транспортов (FIFO, UNIX Domain Sockets, Android Intents).

```
 +-------------------------------------------------------------------------------+
 |                               INPUT SOURCES                                   |
 |  [broadcast_receiver.sh]       [fifo_input.py]       [unix_socket_input.py]   |
 |   (Android am Intents)          (Named FIFO)           (AF_UNIX Stream)       |
 +-----------------------+---------------+-----------------------+---------------+
                         |               |                       |
                         +---------------+-----------------------+
                                         |
                                         v  JSON Event Envelopes
 +-------------------------------------------------------------------------------+
 |                             STREAM INTERCEPTORS                               |
 |   Core Server stdout/stderr ---> [stream_splitter.py]                         |
 |                                     |                                         |
 |             +-----------------------+-----------------------+                 |
 |             v                       v                       v                 |
 |     [filter_open_url.py]  [filter_task_events.py]  [filter_error_alerts.py]   |
 +-------------------------------------+-----------------------------------------+
                                       |
                                       v  envelope.create_event()
 +-------------------------------------------------------------------------------+
 |                               CORE EVENT BUS                                  |
 |                            [event_broker.py]                                  |
 |                                     |                                         |
 |                 +-------------------+-------------------+                     |
 |                 |                                       |                     |
 |                 v                                       v                     |
 |    In-Memory Handlers (Python)            [subscriber_router.py]              |
 |    (broker.register_handler)             (spec/default_routes.json)           |
 +---------------------------------------------------------+---------------------+
                                                           |
                                                           v  Process Dispatch
 +-------------------------------------------------------------------------------+
 |                               OUTPUT HANDLERS                                 |
 |  [notify_tray.sh]      [broadcast_emitter.sh]    [file_sink.py]  [socket_out] |
 |   Android Tray via         ACTION_OPEN_URL       events.jsonl    AF_UNIX IPC  |
 |   agy-notify / am          to Android Activity     Append Log     Forwarding  |
 +-------------------------------------------------------------------------------+
```

---

## 2. Спецификация событий (Event Envelope)

Все события, циркулирующие в шине, строго стандартизированы JSON-схемой `spec/event_schema.json` (Draft-07).

### 2.1. Формат конверта (Envelope)
```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "AntigravityEventEnvelope",
  "type": "object",
  "required": ["event", "timestamp", "source", "data"],
  "properties": {
    "event": {
      "type": "string",
      "description": "Имя события через точку, напр. core.url.open, agent.task.started"
    },
    "timestamp": {
      "type": "integer",
      "description": "Epoch timestamp в миллисекундах"
    },
    "source": {
      "type": "string",
      "description": "Источник события, напр. language_server:stdout, input:socket"
    },
    "data": {
      "type": "object",
      "description": "Произвольный JSON-payload события"
    }
  }
}
```

### 2.2. Сериализация и валидация (`core_bus/envelope.py`)
- `create_event(event_type, source, data)`: создаёт словарь события с текущей миллисекундной меткой `int(time.time() * 1000)`.
- `serialize_event(envelope)`: сериализует событие в UTF-8 JSON строку.
- `deserialize_event(raw_text)`: разбирает JSON и валидирует наличие обязательных полей.
- `validate_event(envelope)`: проверяет наличие полей `event`, `timestamp`, `source`, `data`, непустую строку `event` и тип словаря `data`.

---

## 3. Ядро шины (`core_bus/`)

### 3.1. Маршрутизатор (`subscriber_router.py`)
- Загружает таблицу маршрутов из JSON-файла (`spec/default_routes.json`).
- Поддерживает маски подстановок (wildcards) через `fnmatch.fnmatch` (например, `agent.task.*`, `core.*`, `*`).
- Метод `get_destinations(event_type)` возвращает отсортированный дедуплицированный список выходных скриптов-обработчиков.

#### Таблица маршрутизации по умолчанию (`spec/default_routes.json`)
| Шаблон события (`pattern`) | Обработчики (`outputs`) | Назначение |
| :--- | :--- | :--- |
| `core.url.open` | `broadcast_emitter.sh`, `file_sink.py` | Открытие URL авторизации/OAuth в системном браузере Android |
| `agent.task.*` | `notify_tray.sh`, `file_sink.py` | Уведомление в статус-бар о жизненном цикле задач агента |
| `system.error` | `notify_tray.sh`, `file_sink.py` | Оповещение о критических сбоях и panic ядра |
| `external.*` | `file_sink.py` | Внешние интеграционные события |

### 3.2. Брокер событий (`event_broker.py`)
- Центральный диспетчер событий `EventBroker`.
- Поддерживает два уровня подписчиков:
  1. **In-Memory Handlers**: Python-колбэки (`register_handler(pattern, callable)`), выполняемые синхронно внутри процесса.
  2. **External Script Handlers**: вызовы внешних shell- и python-скриптов из директории `outputs/`.
- Вызов внешних обработчиков осуществляется через `subprocess.Popen` с передачей JSON-конверта через `stdin` и настраиваемым таймаутом выполнения (по умолчанию 5 секунд).
- **Защита от зомби-процессов и утечек FD**: при возникновении `subprocess.TimeoutExpired` гарантированно вызываются `proc.kill()`, `proc.wait()` для утилизации процесса из таблицы ОС и закрываются потоковые дескрипторы `stdin`/`stderr`.
- Учитывает ограничения Android: на `/storage/emulated/0` (монтирование `noexec`) `.sh` файлы запускаются с явным указанием интерпретатора (`/system/bin/sh` или `bash`).

---

## 4. Входные каналы (`inputs/`)

1. **FIFO Pipe Listener (`inputs/fifo_input.py`)**
   - Прослушивает именованный пайп (`mkfifo`), десериализует построчные JSON-события и публикует их в `EventBroker`.
   - Запуск: `python3 fifo_input.py <path_to_fifo>`.
   - **FUSE-безопасность**: функция `get_safe_pipe_path` автоматически перенаправляет пути с FUSE-хранилища (`/storage/emulated/0`, `/sdcard`), где `mkfifo` завершается с `PermissionError`, в безопасный внутренний каталог рантайма (`/data/user/0/.../runtime` или `$TMPDIR`).
   - **Неблокирующая запись**: функция `write_fifo_nonblocking(fifo_path, data)` производит запись через флаг `O_NONBLOCK`, предотвращая зависание при отсутствии активного читателя.

2. **UNIX Domain Socket Listener (`inputs/unix_socket_input.py`)**
   - Сервер потокового UNIX-сокета (`AF_UNIX`, `SOCK_STREAM`).
   - Принимает внешние IPC-подключения, собирает входящий буфер по строкам-разделителям `\n` и отправляет события в брокер.
   - Запуск: `python3 unix_socket_input.py <path_to_socket>`.

3. **Android Broadcast Receiver Bridge (`inputs/broadcast_receiver.sh`)**
   - Утилита для трансляции Android Intent (например, от `am broadcast` или плагинов) в JSON-конверт шины:
     `broadcast_receiver.sh <action> <payload_json> [destination_fifo]`
   - Формирует событие вида `android.intent.<ACTION>` и записывает в пайп шины или выводит в `stdout`.
   - Поддерживает безопасное разрешение FUSE-путей и неблокирующую отправку в FIFO (через Python `O_NONBLOCK` или утилиту `timeout`).

4. **Шаблон кастомного источника (`inputs/templates/template_input.sh`)**
   - Готовая заготовка для создания пользовательских генераторов событий (сенсоры, хуки, вебхуки) со встроенной защитой от блокировок FIFO.

---

## 5. Потоковые перехватчики (`interceptors/`)

Перехватчики используются при запуске серверного ядра (`modules/runner/start_core.sh`), где `stdout` и `stderr` ядра перенаправляются в конвейер `stream_splitter.py`.

### 5.1. Расщепитель потока (`stream_splitter.py`)
- Читает строки из `sys.stdin`.
- Безусловно дублирует их в `sys.stdout` (сохраняя сквозной лог процесса).
- **Асинхронная очередь событий (Queue Worker)**: публикация перехваченных событий вынесена в очередь `queue.Queue` с отдельным фоновым потоком-демоном. Благодаря этому диспетчеризация событий и внешние скрипты-обработчики не блокируют чтение `stdout`/`stderr` серверного ядра.
- Параллельно прогоняет каждую строку через цепочку фильтров `FILTERS`.
- Если фильтр распознает маркер события, формируется конверт `envelope.create_event` и помещается в очередь на публикацию.

### 5.2. Модули фильтров (`interceptors/filters/`)
- `filter_open_url.py`: Перехватывает префиксы `AGY_OPEN_URL=`, `ANTIGRAVITY_OPEN_URL:` и OAuth-ссылки (`accounts.google.com`, `/auth/callback`), генерируя событие `core.url.open`.
- `filter_task_events.py`: Отслеживает фазы задач агента (`[task:start]`, `[task:done]`, `tool_call:`), генерируя `agent.task.started`, `agent.task.completed`, `agent.tool.executed`.
- `filter_error_alerts.py`: Отслеживает фатальные ошибки (`panic:`, `fatal error:`, `sigsegv`, `sigill`), генерируя `system.error`.

---

## 6. Выходные обработчики (`outputs/`)

1. **Android Tray Notifier (`outputs/notify_tray.sh`)**
   - Принимает JSON события из `stdin`.
   - Извлекает имя события и текст сообщения.
   - Отправляет системное уведомление Android через утилиту `agy-notify` или через `am broadcast -a dev.continuum.antigravity.ACTION_NOTIFY`.

2. **Android Browser Intent Emitter (`outputs/broadcast_emitter.sh`)**
   - Принимает событие `core.url.open`.
   - Извлекает поле `url` и отправляет Android Broadcast `dev.continuum.antigravity.ACTION_OPEN_URL` для открытия во внешнем браузере или Custom Tabs.

3. **Persistent JSONL Logger (`outputs/file_sink.py`)**
   - Записывает каждое входящее событие в append-only файл логов (по умолчанию `~/.gemini/events.jsonl` или переменную `AGY_EVENT_LOG`).

4. **UNIX Socket Emitter (`outputs/unix_socket_emitter.py`)**
   - Пересылает события внешним фоновым процессам или демонам через клиентское подключение к UNIX-сокету.

---

## 7. Модульное тестирование (`tests/`)

Тестовый набор покрывает ключевые компоненты шины и проверяет корректность сериализации и маршрутизации.

### 7.1. Запуск всех тестов
```bash
bash modules/io/tests/run_all.sh
```

### 7.2. Состав тестов
| Тестовый файл | Проверяемый компонент | Проверяемый сценарий |
| :--- | :--- | :--- |
| `test_envelope.py` | `envelope.py` | Валидация типов, создание конверта, roundtrip сериализация/десериализация, отсечение невалидных схем |
| `test_event_broker.py` | `SubscriberRouter`, `EventBroker` | Wildcard-фильтрация маршрутов (`*`, `core.*`), in-memory диспетчеризация, таймаут внешних скриптов с вызовом `proc.kill()`/`proc.wait()` и закрытием FD (`test_03_timeout_cleanup`) |
| `test_fifo_io.py` | `fifo_input.py`, `EventBroker` | Интеграционный тест: создание FIFO, фоновый поток слушателя, публикация и прием события, перенаправление FUSE-путей, неблокирующая запись без читателя |
| `test_stream_splitter.py` | `stream_splitter.py`, фильтры | Прогон через `io.StringIO`: сохранение исходного потока + генерация `core.url.open` и `system.error`, асинхронная очередь событий через `queue.Queue` без задержек потока ядра |
| `run_all.sh` | Все компоненты | Синтаксическая валидация Python (`compileall`) и Bash (`bash -n`) + последовательный запуск модульных тестов |

---

## 8. Аудит надёжности и технический долг

В ходе аудита были выявлены и устранены критические архитектурные и платформенные проблемы:

### 8.1. Разбор JSON в Shell-скриптах [РЕШЕНО]
- **Проблема**: В `notify_tray.sh`, `broadcast_emitter.sh` и `template_output.sh` использовался наивный парсинг JSON через `grep -o '"url":"[^"]*'`. Стандартный Python `json.dumps()` вставляет пробел после двоеточия (`"url": "..."`), из-за чего поля не извлекались.
- **Решение**: Регулярные выражения обновлены с поддержкой произвольных пробельных символов `[[:space:]]*:[[:space:]]*` (`"url"[[:space:]]*:[[:space:]]*"[^"]*`). Проверена совместимость с `/system/bin/sh` (Toybox) и Bash на Android.

### 8.2. Блокирующие вызовы и риск зависания ядра (Head-of-Line Blocking) [РЕШЕНО]
- **Проблема**: `stream_splitter.py` подключен напрямую в `stdout` основного сервера ядра. При обнаружении URL или ошибки синхронный вызов `broker.publish()` приводил к заполнению буфера пайпа Linux (64 КБ) и блокировке ядра.
- **Решение**: Публикация событий в `stream_splitter.py` переведена на асинхронную модель с использованием `queue.Queue` и фонового потока-демона. Потоки `stdout`/`stderr` вычитываются и сбрасываются мгновенно.

### 8.3. Утечка файловых дескрипторов и зомби-процессы при таймауте `subprocess.Popen` [РЕШЕНО]
- **Проблема**: В `event_broker.py` при таймауте процесса `subprocess.TimeoutExpired` дочерний процесс продолжал висеть в фоне, а дескрипторы `stdin`/`stderr` не закрывались.
- **Решение**: Добавлен специализированный блок `except subprocess.TimeoutExpired`, гарантированно вызывающий `proc.kill()`, `proc.wait()` (устраняющий зомби в таблице процессов ОС) и закрывающий открытые файловые дескрипторы `stdin` и `stderr`.

### 8.4. Блокировка записи в FIFO без читателя [РЕШЕНО]
- **Проблема**: В `broadcast_receiver.sh` и шаблонах прямая запись `echo "$JSON" > "$FIFO"` блокировалась в соответствии со спецификацией POSIX до подключения читателя.
- **Решение**: В `broadcast_receiver.sh` и `template_input.sh` добавлена неблокирующая отправка через Python `os.O_NONBLOCK` или утилиту `timeout 1s`. В `fifo_input.py` добавлена функция `write_fifo_nonblocking`.

### 8.5. Несовместимость FIFO и UNIX-сокетов с Android FUSE [РЕШЕНО]
- **Проблема**: Попытка создать именованный пайп (`mkfifo`) на `/storage/emulated/0/...` вызывала `PermissionError: [Errno 13] Permission denied`.
- **Решение**: Реализовано автоматическое распознавание FUSE-префиксов (`/storage/`, `/sdcard/`, `/mnt/`) в `get_safe_pipe_path` (`fifo_input.py`) и `broadcast_receiver.sh`, с прозрачным перенаправлением пайпов во внутреннее хранилище приложения (`/data/user/0/.../runtime` или `$TMPDIR`).

### 8.6. Дублирование кода с Java-уровнем
- **Статус**: Архитектурный бэклог. Логика распознавания ссылок и префиксов в `filter_open_url.py` дублирует `OpenUrlWatcher.java`. Рекомендуется использовать единую шину через `broadcast_receiver.sh` в последующих заходах.
