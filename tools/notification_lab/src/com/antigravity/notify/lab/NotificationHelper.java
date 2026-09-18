package com.antigravity.notify.lab;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;

public class NotificationHelper {

    public static final String CH_AGENT = "antigravity_agent";
    public static final String CH_SERVICE = "antigravity_service";

    // Фирменный глубокий синий оттенок Google DeepMind
    public static final int COLOR_ANTIGRAVITY = 0xFF4285F4;

    private final Context context;
    private final NotificationManager nm;

    public NotificationHelper(Context context) {
        this.context = context;
        this.nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        initChannels();
    }

    private void initChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel agent = new NotificationChannel(
                    CH_AGENT,
                    "События агента",
                    NotificationManager.IMPORTANCE_HIGH
            );
            agent.setDescription("Завершение задач, вопросы и результаты работы агента");
            agent.enableVibration(true);
            agent.setVibrationPattern(new long[]{0, 160, 60, 160});
            agent.setShowBadge(true);
            nm.createNotificationChannel(agent);

            NotificationChannel service = new NotificationChannel(
                    CH_SERVICE,
                    "Фоновый сервис",
                    NotificationManager.IMPORTANCE_LOW
            );
            service.setShowBadge(false);
            nm.createNotificationChannel(service);
        }
    }

    /**
     * Элегантное минималистичное уведомление о завершении задачи.
     * Чистый заголовок, четкая суть и одна аккуратная кнопка «Открыть».
     */
    public void showTaskDone(int id, String title, String message, String details) {
        PendingIntent piOpen = getOpenIntent(id);

        Notification.Builder b = getBuilder(CH_AGENT, Notification.PRIORITY_HIGH)
                .setSmallIcon(R.drawable.ic_antigravity_small)
                .setColor(COLOR_ANTIGRAVITY)
                .setContentTitle(title)
                .setContentText(message)
                .setSubText("ИИ-Ассистент")
                .setContentIntent(piOpen)
                .setAutoCancel(true);

        // Аккуратная разворачиваемая сводка
        if (details != null && !details.isEmpty()) {
            b.setStyle(new Notification.BigTextStyle()
                    .setBigContentTitle(title)
                    .bigText(message + "\n\n" + details));
        }

        // Лаконичное быстрое действие
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            b.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_action_chat),
                    "Открыть", piOpen).build());
        }

        nm.notify(id, b.build());
    }

    /**
     * Уведомление с запросом подтверждения: две компактные кнопки действий.
     */
    public void showActionRequired(int id, String title, String question) {
        PendingIntent piOpen = getOpenIntent(id);

        Intent approveIntent = new Intent("com.antigravity.notify.lab.ACTION_APPROVE");
        PendingIntent piApprove = PendingIntent.getBroadcast(context, id * 10 + 1, approveIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stopIntent = new Intent("com.antigravity.notify.lab.ACTION_STOP");
        PendingIntent piStop = PendingIntent.getBroadcast(context, id * 10 + 2, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder b = getBuilder(CH_AGENT, Notification.PRIORITY_HIGH)
                .setSmallIcon(R.drawable.ic_antigravity_small)
                .setColor(COLOR_ANTIGRAVITY)
                .setContentTitle(title)
                .setContentText(question)
                .setSubText("Требуется ответ")
                .setContentIntent(piOpen)
                .setAutoCancel(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            b.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_action_check),
                    "Одобрить", piApprove).build());

            b.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_action_stop),
                    "Отмена", piStop).build());
        }

        nm.notify(id, b.build());
    }

    /**
     * Минималистичный прогресс фоновой операции.
     */
    public void showProgress(int id, String taskName, int progress) {
        Notification.Builder b = getBuilder(CH_SERVICE, Notification.PRIORITY_LOW)
                .setSmallIcon(R.drawable.ic_antigravity_small)
                .setColor(COLOR_ANTIGRAVITY)
                .setContentTitle(taskName)
                .setContentText(progress + "%")
                .setProgress(100, progress, false)
                .setOngoing(progress < 100);

        nm.notify(id, b.build());
    }

    private Notification.Builder getBuilder(String channelId, int priority) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(context, channelId);
        } else {
            Notification.Builder b = new Notification.Builder(context);
            b.setPriority(priority);
            return b;
        }
    }

    private PendingIntent getOpenIntent(int id) {
        Intent intent = new Intent(context, MainActivity.class);
        return PendingIntent.getActivity(
                context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    public void cancel(int id) {
        nm.cancel(id);
    }

    public void cancelAll() {
        nm.cancelAll();
    }
}
