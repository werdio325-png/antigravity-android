package com.antigravity.notify.lab;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private NotificationHelper helper;
    private EditText etTitle;
    private EditText etMessage;
    private TextView tvCliCmd;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        helper = new NotificationHelper(this);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 101);
            }
        }

        initViews();
    }

    private void initViews() {
        etTitle = findViewById(R.id.et_title);
        etMessage = findViewById(R.id.et_message);
        tvCliCmd = findViewById(R.id.tv_cli_cmd);

        etTitle.setText("Задача выполнена");
        etMessage.setText("Код скомпилирован и проверен");

        // 1. Быстрые уведомления
        findViewById(R.id.btn_preset_headsup).setOnClickListener(v -> {
            helper.showTaskDone(100, "Задача выполнена", "Код скомпилирован и проверен", "Все 5 проверок пройдены успешно без сбоев.");
            Toast.makeText(this, "Уведомление отправлено", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_preset_actions).setOnClickListener(v -> {
            helper.showActionRequired(101, "Требуется подтверждение", "Запустить публикацию релиза v2.14.0 на GitHub?");
            Toast.makeText(this, "Уведомление с кнопками создано", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_preset_bigtext).setOnClickListener(v -> {
            helper.showTaskDone(102, "Отчет о тестировании", "5 тестов успешно завершены", "• Core: ARM64 v2.14.0 (169.5 MB)\n• Patches: LSE, BypassRegion [OK]\n• Output: Antigravity-v2.14.0.apk");
            Toast.makeText(this, "Уведомление отправлено", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_preset_progress).setOnClickListener(v -> simulateProgress());

        findViewById(R.id.btn_preset_ongoing).setOnClickListener(v -> {
            helper.showProgress(104, "Служба Antigravity активна", 100);
            Toast.makeText(this, "Статус закреплен", Toast.LENGTH_SHORT).show();
        });

        // 2. Кастомное
        findViewById(R.id.btn_send_custom).setOnClickListener(v -> {
            String title = etTitle.getText().toString().trim();
            if (title.isEmpty()) title = "Antigravity";
            String msg = etMessage.getText().toString().trim();
            if (msg.isEmpty()) msg = "Задача завершена";

            helper.showTaskDone(200, title, msg, null);
            Toast.makeText(this, "Отправлено", Toast.LENGTH_SHORT).show();
        });

        // 3. Копия команды
        findViewById(R.id.btn_copy_cmd).setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            String cmd = "am broadcast -a com.antigravity.notify.lab.SEND --es title '" + etTitle.getText() + "' --es message '" + etMessage.getText() + "'";
            ClipData clip = ClipData.newPlainText("cmd", cmd);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "Команда скопирована", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_clear_all).setOnClickListener(v -> {
            helper.cancelAll();
            Toast.makeText(this, "Очищено", Toast.LENGTH_SHORT).show();
        });
    }

    private void simulateProgress() {
        Toast.makeText(this, "Прогресс запущен", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            for (int p = 0; p <= 100; p += 20) {
                final int prog = p;
                handler.post(() -> {
                    if (prog < 100) {
                        helper.showProgress(103, "Сборка APK", prog);
                    } else {
                        helper.cancel(103);
                        helper.showTaskDone(103, "Сборка завершена", "Файл Antigravity-v2.14.0.apk готов к работе.", null);
                    }
                });
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {}
            }
        }).start();
    }
}
