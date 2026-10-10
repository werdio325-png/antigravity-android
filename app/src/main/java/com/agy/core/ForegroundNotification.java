package com.agy.core;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.pm.ServiceInfo;
import android.os.Build;

/** Builds the low-importance ongoing notification and enters the foreground. */
public final class ForegroundNotification {

    public static final int NOTIFICATION_ID = 1001;
    public static final String CHANNEL_ID = "core_channel";

    private ForegroundNotification() {
    }

    public static void start(Service service) {
        NotificationManager manager =
                (NotificationManager) service.getSystemService(Service.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Core", NotificationManager.IMPORTANCE_LOW);
            manager.createNotificationChannel(channel);
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(service, CHANNEL_ID)
                : new Notification.Builder(service);
        Notification notification = builder
                .setContentTitle("Antigravity")
                .setContentText("Core running")
                .setSmallIcon(service.getResources().getIdentifier("ic_notification", "drawable", service.getPackageName()) != 0
                        ? service.getResources().getIdentifier("ic_notification", "drawable", service.getPackageName())
                        : android.R.drawable.stat_notify_sync)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            service.startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            service.startForeground(NOTIFICATION_ID, notification);
        }
    }
}
