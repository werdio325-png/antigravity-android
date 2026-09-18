package com.antigravity.notify.lab;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

public class NotificationReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;

        NotificationHelper helper = new NotificationHelper(context);

        if ("com.antigravity.notify.lab.SEND".equals(action)) {
            String title = intent.getStringExtra("title");
            if (title == null || title.isEmpty()) title = "Задача выполнена";
            String message = intent.getStringExtra("message");
            if (message == null || message.isEmpty()) message = "Изменения успешно применены и протестированы";
            String details = intent.getStringExtra("details");

            String type = intent.getStringExtra("type");
            if ("action".equalsIgnoreCase(type)) {
                helper.showActionRequired(101, title, message);
            } else if ("progress".equalsIgnoreCase(type)) {
                int p = intent.getIntExtra("progress", 50);
                helper.showProgress(102, title, p);
            } else {
                helper.showTaskDone(100, title, message, details);
            }

        } else if ("com.antigravity.notify.lab.ACTION_APPROVE".equals(action)) {
            Toast.makeText(context, "✓ Подтверждено", Toast.LENGTH_SHORT).show();
            helper.cancel(101);

        } else if ("com.antigravity.notify.lab.ACTION_STOP".equals(action)) {
            Toast.makeText(context, "Отменено", Toast.LENGTH_SHORT).show();
            helper.cancel(101);
        }
    }
}
