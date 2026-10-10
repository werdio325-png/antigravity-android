<div align="center">

  <!-- Language Switcher -->
  <p>
    <a href="README.md"><font color="#7d8590">English</font></a> &nbsp;&bull;&nbsp; <b>Русский</b>
  </p>

  <br/>

  <img src="docs/assets/logo.png" alt="Antigravity" width="96" height="96" style="border-radius: 22px; margin-bottom: 12px;" />

  <p><font size="6"><b>Antigravity для Android</b></font></p>

  <p><font color="#7d8590">Автономная среда разработки на базе ИИ-агентов, реализованная нативно для Android и ARM64.</font></p>

  <p>
    <code>Android 7.0+</code> &nbsp;&bull;&nbsp;
    <code>ARM64 v8.1-A+</code> &nbsp;&bull;&nbsp;
    <code>Shizuku Ready</code> &nbsp;&bull;&nbsp;
    <code>Native Bionic</code> &nbsp;&bull;&nbsp;
    <code>Consistent Keystore</code> &nbsp;&bull;&nbsp;
    <code>Apache 2.0</code>
  </p>

  <br/>

  <!-- Hero Visual -->
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/demo_benchmark_dark.png">
    <source media="(prefers-color-scheme: light)" srcset="docs/assets/demo_benchmark_light.png">
    <img alt="Интерфейс Antigravity в работе" src="docs/assets/demo_benchmark_dark.png" width="100%" style="border-radius: 14px; border: 1px solid rgba(128, 128, 128, 0.2); box-shadow: 0 12px 32px rgba(0, 0, 0, 0.35);" />
  </picture>
  <p align="center"><font size="2" color="#7d8590">Автономное выполнение CLI-диагностики накопителя агентом на физическом устройстве (Linux 6.1, AArch64)</font></p>

</div>

---

<p><font size="5"><b>Архитектурный обзор</b></font></p>

**Antigravity для Android** переносит платформу автономного агентного программирования от Google непосредственно в мобильную операционную систему.

В отличие от терминальных чат-клиентов или виртуализованных окружений (PRoot, QEMU, chroot), Antigravity исполняется **нативно в пространстве пользователя Android (Bionic userspace)** в рамках процесса приложения. Агент на базе моделей Gemini самостоятельно исследует кодовую базу, редактирует файлы проекта, запускает системные компиляторы и валидирует результаты непосредственно на целевом мобильном оборудовании.

> [!NOTE]
> **Прямое исполнение без виртуализации:** Процессы запускаются нативно через гибридный Bionic/Glibc загрузчик без накладных расходов эмуляции, используя аппаратную скорость хранилища UFS и инструкции Large System Extensions (LSE) архитектуры ARMv8.1-A+.

---

<p><font size="5"><b>Ключевые возможности</b></font></p>

<table width="100%" style="border-collapse: separate; border-spacing: 8px; border: none;">
  <tr>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>Автономный агент Gemini</b></font></p>
      <p><font color="#7d8590">Полный цикл разработки: декомпозиция задач, многофайловые правки, компиляция исходного кода, выполнение тестов и отладка ошибок сборки.</font></p>
    </td>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>Привилегии Shizuku (Rish)</b></font></p>
      <p><font color="#7d8590">Повышение привилегий до уровня оболочки ADB (<code>uid=2000</code>) без root: установка пакетов (<code>pm install</code>), доступ к системным журналам и внешним накопителям.</font></p>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>Нативный менеджер пакетов</b></font></p>
      <p><font color="#7d8590">Легковесный пакетный менеджер <code>pkg</code> (tlx) на Bash и AWK. Прямая установка инструментариев (<code>git</code>, <code>python</code>, <code>nodejs</code>, <code>clang</code>) из репозиториев Termux.</font></p>
    </td>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>Адаптивные режимы Mobile & Desktop</b></font></p>
      <p><font color="#7d8590">Сенсорный мобильный интерфейс по умолчанию и масштабированный десктопный режим с динамическим переключением User-Agent для планшетов и Samsung DeX.</font></p>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>Системная осведомлённость агента</b></font></p>
      <p><font color="#7d8590">Автоматически инициализируемые правила (<code>AGENTS.md</code>) информируют модель о путях префикса, синтаксисе пакетов и блокируют несовместимые настольные песочницы.</font></p>
    </td>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>Бесшовные обновления</b></font></p>
      <p><font color="#7d8590">Интегрированный стабильный ключ подписи (Keystore): новые релизы APK обновляются поверх существующей версии с сохранением проектов и сессий.</font></p>
    </td>
  </tr>
</table>

---

<p><font size="5"><b>Визуальный обзор возможностей</b></font></p>

### 1. Рабочее пространство разработчика

Пользовательский интерфейс адаптирован для мобильных экранов: боковая панель проектов, навигация по сессиям диалога, селектор моделей Gemini и синхронизация системной темы оформления.

<br/>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/workspace_dark.png">
  <source media="(prefers-color-scheme: light)" srcset="docs/assets/workspace_light.png">
  <img alt="Рабочее пространство Antigravity" src="docs/assets/workspace_dark.png" width="100%" style="border-radius: 12px; border: 1px solid rgba(128, 128, 128, 0.2); box-shadow: 0 8px 24px rgba(0,0,0,0.25);" />
</picture>
<p align="center"><font size="2" color="#7d8590">Навигационная панель и активное рабочее пространство (тёмная и светлая темы)</font></p>

<br/>

---

### 2. Управление отображением: Mobile & Desktop Mode

В меню настроек внешнего вида (*Appearance*) встроен переключатель **Desktop Mode**:
* **Mobile Mode (по умолчанию):** Крупные элементы сенсорного ввода, оптимизированные для управления одной рукой и работы с виртуальной клавиатурой.
* **Desktop Mode:** Расширенный вьюпорт с десктопным User-Agent и широким обзором редактора (для планшетов, физических клавиатур и режима Samsung DeX).
* *Переключение применяется динамически без перезапуска приложения через автоматическую перезагрузку WebView.*

<br/>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/settings_desktop_mode_dark.png">
  <source media="(prefers-color-scheme: light)" srcset="docs/assets/settings_desktop_mode_light.png">
  <img alt="Настройки Appearance и переключатель Desktop Mode" src="docs/assets/settings_desktop_mode_dark.png" width="100%" style="border-radius: 12px; border: 1px solid rgba(128, 128, 128, 0.2); box-shadow: 0 8px 24px rgba(0,0,0,0.25);" />
</picture>
<p align="center"><font size="2" color="#7d8590">Панель Appearance с интегрированным переключателем Desktop Mode</font></p>

<br/>

---

### 3. Автономная работа агента в терминале

Агент напрямую взаимодействует с системой: вызывает бинарники в userspace, создаёт изолированные структуры каталогов, компилирует код и анализирует диагностические выводы без участия пользователя.

<br/>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/demo_exec_dark.png">
  <source media="(prefers-color-scheme: light)" srcset="docs/assets/demo_exec_light.png">
  <img alt="Исполнение терминальных команд агентом" src="docs/assets/demo_exec_dark.png" width="100%" style="border-radius: 12px; border: 1px solid rgba(128, 128, 128, 0.2); box-shadow: 0 8px 24px rgba(0,0,0,0.25);" />
</picture>
<p align="center"><font size="2" color="#7d8590">Агент инициализирует структуру проекта и запрашивает параметры ядра Android 6.1</font></p>

<br/>

---

### 4. Аппаратная производительность накопителя

В ходе тестирования агент разработал на Python специализированную утилиту замера пропускной способности хранилища и провёл стресс-тест. Скорость последовательного чтения превысила **1222 МБ/с**, а случайная запись достигла **84 395 IOPS**, подтверждая нативную производительность шины UFS без накладных расходов виртуализации.

<br/>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/demo_benchmark_dark.png">
  <source media="(prefers-color-scheme: light)" srcset="docs/assets/demo_benchmark_light.png">
  <img alt="Результаты бенчмарка памяти агентом" src="docs/assets/demo_benchmark_dark.png" width="100%" style="border-radius: 12px; border: 1px solid rgba(128, 128, 128, 0.2); box-shadow: 0 8px 24px rgba(0,0,0,0.25);" />
</picture>
<p align="center"><font size="2" color="#7d8590">Итоговый отчёт бенчмарка с табличным форматированием и рекомендациями по рабочей области</font></p>

<br/>

---

<p><font size="5"><b>Установка и первый запуск</b></font></p>

<table width="100%" style="border-collapse: separate; border-spacing: 8px; border: none;">
  <tr>
    <td width="33%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>1. Загрузка APK</b></font></p>
      <p><font color="#7d8590">В разделе <a href="https://github.com/werdio325-png/antigravity-android/releases">Releases</a> представлены релизные пакеты:<br/><br/>
      &bull; <b><code>antigravity.apk</code></b> — стандартная версия (порт 45157).<br/>
      &bull; <b><code>antigravity-bypass.apk</code></b> — сборка с патчем региональных ограничений.<br/><br/>
      <i>Поддерживается бесшовное обновление поверх старых сборок.</i></font></p>
    </td>
    <td width="33%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>2. Настройка прав</b></font></p>
      <p><font color="#7d8590">Предоставьте разрешение на доступ к хранилищу (<code>MANAGE_EXTERNAL_STORAGE</code>).<br/><br/>
      <i>(Опционально)</i> Запустите службу <b>Shizuku</b> и подтвердите доступ для взаимодействия с оболочкой ADB без root.</font></p>
    </td>
    <td width="33%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>3. Установка инструментов</b></font></p>
      <p><font color="#7d8590">Установите требуемый инструментарий через встроенный терминал:<br/><br/>
      <code>pkg update</code><br/>
      <code>pkg install git python nodejs</code><br/><br/>
      Среда готова к компиляции и разработке.</font></p>
    </td>
  </tr>
</table>

---

<p><font size="5"><b>Системные требования</b></font></p>

<table width="100%" style="border-collapse: separate; border-spacing: 0; border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; overflow: hidden;">
  <thead>
    <tr>
      <th style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">Компонент</th>
      <th style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">Минимальные требования</th>
      <th style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">Рекомендуемые требования</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);"><b>Архитектура процессора</b></td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);"><b>ARMv8.1-A+</b> (Cortex-A55, A75, Kryo 300+)</td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);"><b>ARMv8.2-A / ARMv9</b> (Snapdragon 8 Gen 1+, Dimensity, Tensor)</td>
    </tr>
    <tr>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);"><b>Оперативная память</b></td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">4 ГБ LPDDR4X</td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">8 ГБ+ LPDDR5 / LPDDR5X</td>
    </tr>
    <tr>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);"><b>Свободная дисковая память</b></td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">2 ГБ (ядро платформы)</td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">8 ГБ+ UFS (для пакетов и кэша сборки)</td>
    </tr>
    <tr>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);"><b>Версия платформы Android</b></td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">Android 7.0 (API 24)</td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">Android 12 – 16+</td>
    </tr>
    <tr>
      <td style="padding: 12px 16px;"><b>Привилегии доступа</b></td>
      <td style="padding: 12px 16px;">Стандартный пользователь</td>
      <td style="padding: 12px 16px;">Shizuku (Активный сервис ADB)</td>
    </tr>
  </tbody>
</table>

---

<p><font size="5"><b>Структура репозитория</b></font></p>

```text
antigravity-android/
├── app/        # Android Host, WebView и супервизор процессов
├── build/      # Детерминированный сборочный конвейер без Gradle (aapt, javac, d8, apksigner)
├── bus/        # Shell-шина, демоны IPC и композиторы переменных
├── config/     # Централизованные конфигурации окружения (app.env, paths.env, tools.env)
├── docs/       # Архитектурные спецификации, технические описания и медиа-ассеты
├── env/        # Bionic-тулчейн, оверлей файловой системы и пакетный менеджер pkg
├── patches/    # Низкоуровневые бинарные патчи (Go pclntab, обход seccomp, AArch64)
└── web/        # Веб-интерфейс, эргономика сенсорного ввода и динамический патчер
```

---

<div align="center">
  <p><font color="#7d8590">Проект распространяется под условиями лицензии <a href="LICENSE">Apache License 2.0</a></font></p>
</div>
