<div align="center">

  <!-- Language Switcher -->
  <p>
    <a href="README.md"><font color="#7d8590">English</font></a> &nbsp;&bull;&nbsp; <b>Русский</b>
  </p>

  <br/>

  <img src="docs/assets/logo.png" alt="Antigravity" width="96" height="96" style="border-radius: 22px; margin-bottom: 12px;" />

  <p><font size="6"><b>Antigravity для Android</b></font></p>

  <p><font color="#7d8590">Автономная среда ИИ-разработки, созданная нативно для Android и ARM64.</font></p>

  <p>
    <code>Android 7.0+</code> &nbsp;&bull;&nbsp;
    <code>ARM64 v8.1-A+</code> &nbsp;&bull;&nbsp;
    <code>Shizuku Ready</code> &nbsp;&bull;&nbsp;
    <code>Native Bionic</code> &nbsp;&bull;&nbsp;
    <code>Seamless Upgrades</code> &nbsp;&bull;&nbsp;
    <code>Apache 2.0</code>
  </p>

  <br/>

  <!-- Hero Visual -->
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/demo_benchmark_dark.png">
    <source media="(prefers-color-scheme: light)" srcset="docs/assets/demo_benchmark_light.png">
    <img alt="Интерфейс Antigravity в работе" src="docs/assets/demo_benchmark_dark.png" width="100%" style="border-radius: 14px; border: 1px solid rgba(128, 128, 128, 0.2); box-shadow: 0 12px 32px rgba(0, 0, 0, 0.35);" />
  </picture>

</div>

---

<p><font size="5"><b>О платформе</b></font></p>

**Antigravity для Android** переносит автономную агентную среду разработки от Google прямо на мобильные устройства и планшеты.

В отличие от простых чат-оболочек или тяжелых виртуальных контейнеров, Antigravity функционирует **нативно в пространстве пользователя Android** без PRoot, QEMU и накладных расходов трансляции. Интегрированное ядро Gemini анализирует кодовые базы, редактирует дерево проекта, выполняет команды в терминале и тестирует приложения прямо на физическом мобильном железе.

> [!NOTE]
> **Нативная производительность без эмуляции:** Процессы исполняются напрямую на процессоре устройства через Bionic/Glibc userspace, используя аппаратную скорость памяти UFS (более 1.2 ГБ/с) и нативные инструкции ARMv8.1-A+ LSE.

---

<p><font size="5"><b>Ключевые возможности</b></font></p>

<table width="100%" style="border-collapse: separate; border-spacing: 8px; border: none;">
  <tr>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>🤖 Автономный агент Gemini</b></font></p>
      <p><font color="#7d8590">Полный цикл разработки: декомпозиция задач, многофайловые правки, компиляция кода, запуск тестов и исправление ошибок.</font></p>
    </td>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>⚡ Привилегии Shizuku (Rish)</b></font></p>
      <p><font color="#7d8590">Повышение прав до уровня ADB (<code>uid=2000</code>) без root: установка APK (<code>pm install</code>), доступ к системным логам и общим директориям.</font></p>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>📦 Нативный менеджер пакетов</b></font></p>
      <p><font color="#7d8590">Встроенный быстрый менеджер <code>pkg</code> на чистом Bash & AWK. Мгновенная установка <code>git</code>, <code>python</code>, <code>nodejs</code>, <code>clang</code> из зеркал Termux.</font></p>
    </td>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>📱 Два режима: Mobile & Desktop</b></font></p>
      <p><font color="#7d8590">Крупный сенсорный интерфейс по умолчанию для телефонов и масштабированный десктопный режим для планшетов, клавиатур и Samsung DeX.</font></p>
    </td>
  </tr>
  <tr>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>🧠 Осведомлённость об Android</b></font></p>
      <p><font color="#7d8590">Предустановленный системный контекст (<code>AGENTS.md</code>) обучает агента архитектуре Android, путям префикса и предотвращает ошибки песочницы.</font></p>
    </td>
    <td width="50%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>🔄 Бесшовные обновления</b></font></p>
      <p><font color="#7d8590">Единый релизный ключ подписи (Keystore): новые версии APK обновляются поверх без удаления и без потери локальных данных и сессий.</font></p>
    </td>
  </tr>
</table>

---

<p><font size="5"><b>Поэтапная презентация продукта</b></font></p>

### Этап 1. Рабочее пространство разработчика

Интерфейс спроектирован специально для мобильных устройств: боковая панель проектов, удобное переключение сессий, выбор моделей Gemini и поддержка как тёмной, так и светлой системной темы.

<br/>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/workspace_dark.png">
  <source media="(prefers-color-scheme: light)" srcset="docs/assets/workspace_light.png">
  <img alt="Рабочее пространство Antigravity" src="docs/assets/workspace_dark.png" width="100%" style="border-radius: 12px; border: 1px solid rgba(128, 128, 128, 0.2); box-shadow: 0 8px 24px rgba(0,0,0,0.25);" />
</picture>

<br/>

---

### Этап 2. Адаптивность: переключение Mobile & Desktop Mode

В настройках внешнего вида (*Appearance*) доступен моментальный переключатель **Desktop Mode**:
* **Mobile Mode (по умолчанию):** Крупные элементы управления, оптимизация под управление одной рукой и виртуальную клавиатуру.
* **Desktop Mode:** Полноценный рабочий стол с широким обзором кода и десктопным User-Agent (идеально при подключении клавиатуры, мыши или внешнего монитора).
* *Переключение перезагружает вьюпорт на лету без перезапуска всего приложения.*

<br/>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/settings_desktop_mode_dark.png">
  <source media="(prefers-color-scheme: light)" srcset="docs/assets/settings_desktop_mode_light.png">
  <img alt="Настройки Appearance и переключатель Desktop Mode" src="docs/assets/settings_desktop_mode_dark.png" width="100%" style="border-radius: 12px; border: 1px solid rgba(128, 128, 128, 0.2); box-shadow: 0 8px 24px rgba(0,0,0,0.25);" />
</picture>

<br/>

---

### Этап 3. Автономный агент в процессе работы

Агент не просто генерирует код, а самостоятельно исследует систему: выполняет терминальные команды в среде Bionic, создаёт изолированные директории для разработки, запускает скрипты и анализирует вывод ошибок в реальном времени.

<br/>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/demo_exec_dark.png">
  <source media="(prefers-color-scheme: light)" srcset="docs/assets/demo_exec_light.png">
  <img alt="Исполнение терминальных команд агентом" src="docs/assets/demo_exec_dark.png" width="100%" style="border-radius: 12px; border: 1px solid rgba(128, 128, 128, 0.2); box-shadow: 0 8px 24px rgba(0,0,0,0.25);" />
</picture>

<br/>

---

### Этап 4. Системная мощь и реальные результаты

Пример реальной демонстрации: агент по запросу написал на Python инструмент замера скорости внутреннего накопителя UFS, выполнил тесты и сформировал сводный отчёт. Скорость последовательного чтения превысила **1.2 ГБ/с**, а случайной записи достигла **84 000 IOPS** — подтверждая производительность нативного выполнения кода на Android.

<br/>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/assets/demo_benchmark_dark.png">
  <source media="(prefers-color-scheme: light)" srcset="docs/assets/demo_benchmark_light.png">
  <img alt="Результаты бенчмарка памяти агентом" src="docs/assets/demo_benchmark_dark.png" width="100%" style="border-radius: 12px; border: 1px solid rgba(128, 128, 128, 0.2); box-shadow: 0 8px 24px rgba(0,0,0,0.25);" />
</picture>

<br/>

---

<p><font size="5"><b>Пошаговый быстрый старт</b></font></p>

<table width="100%" style="border-collapse: separate; border-spacing: 8px; border: none;">
  <tr>
    <td width="33%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>1. Скачать релизный APK</b></font></p>
      <p><font color="#7d8590">В разделе <a href="https://github.com/werdio325-png/antigravity-android/releases">Релизов</a> доступны сборки:<br/><br/>
      &bull; <b><code>antigravity.apk</code></b> — стандартная версия.<br/>
      &bull; <b><code>antigravity-bypass.apk</code></b> — сборка с региональным обходом.<br/><br/>
      <i>Обновления можно ставить поверх без потери сессий.</i></font></p>
    </td>
    <td width="33%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>2. Выдать разрешения</b></font></p>
      <p><font color="#7d8590">При первом входе предоставьте доступ ко всем файлам (<code>MANAGE_EXTERNAL_STORAGE</code>).<br/><br/>
      <i>(Опционально)</i> Запустите <b>Shizuku</b> и выдайте права приложению для доступа к ADB Shell без root-прав.</font></p>
    </td>
    <td width="33%" valign="top" style="border: 1px solid rgba(128, 128, 128, 0.2); border-radius: 12px; padding: 16px;">
      <p><font size="4"><b>3. Установить окружение</b></font></p>
      <p><font color="#7d8590">Попросите агента поставить нужные пакеты или выполните сами в терминале:<br/><br/>
      <code>pkg update</code><br/>
      <code>pkg install git python nodejs</code><br/><br/>
      Среда готова к полноценной разработке!</font></p>
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
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);"><b>Архитектура CPU</b></td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);"><b>ARMv8.1-A+</b> (Cortex-A55, A75, Kryo 300+)</td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);"><b>ARMv8.2-A / ARMv9</b> (Snapdragon 8 Gen 1+, Dimensity, Tensor)</td>
    </tr>
    <tr>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);"><b>Оперативная память</b></td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">4 ГБ LPDDR4X</td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">8 ГБ+ LPDDR5 / LPDDR5X</td>
    </tr>
    <tr>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);"><b>Свободная память</b></td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">2 ГБ (ядро среды)</td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">8 ГБ+ UFS (для пакетов и кэша сборки)</td>
    </tr>
    <tr>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);"><b>Версия Android</b></td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">Android 7.0 (API 24)</td>
      <td style="padding: 12px 16px; border-bottom: 1px solid rgba(128, 128, 128, 0.2);">Android 12 – 16+</td>
    </tr>
    <tr>
      <td style="padding: 12px 16px;"><b>Привилегии</b></td>
      <td style="padding: 12px 16px;">Обычный пользователь</td>
      <td style="padding: 12px 16px;">Shizuku (Активный сервис ADB)</td>
    </tr>
  </tbody>
</table>

---

<p><font size="5"><b>Структура репозитория</b></font></p>

```text
antigravity-android/
├── app/        # Android Host, WebView и супервизор процессов
├── build/      # Детерминированная сборка без Gradle (aapt, javac, d8, apksigner)
├── bus/        # Shell-шина, демоны IPC и композиторы переменных
├── config/     # Централизованные конфиги окружения (app.env, paths.env, tools.env)
├── docs/       # Спецификации архитектуры, руководства и медиа-ассеты
├── env/        # Тулчейн Bionic, оверлей файловой системы и пакетный менеджер pkg
├── patches/    # Низкоуровневые патчи (Go pclntab, обход seccomp, шлюзы AArch64)
└── web/        # Веб-интерфейс, эргономика сенсорного ввода и динамический патчер
```

---

<div align="center">
  <p><font color="#7d8590">Проект распространяется под лицензией <a href="LICENSE">Apache License 2.0</a></font></p>
</div>
