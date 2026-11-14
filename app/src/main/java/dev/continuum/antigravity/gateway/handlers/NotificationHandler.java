package dev.continuum.antigravity.gateway.handlers;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

/**
 * Handles native Android notifications (title, message, progress, channels, cancel).
 */
public final class NotificationHandler {

    private static final String TAG = "NotificationHandler";
    public static final String CHANNEL_TASKS = "agy_tasks";
    public static final String CHANNEL_ALERTS = "agy_alerts";

    private final Context context;
    private final NotificationManager manager;

    public NotificationHandler(Context context) {
        this.context = context.getApplicationContext();
        this.manager = (NotificationManager) this.context.getSystemService(Context.NOTIFICATION_SERVICE);
        initChannels();
    }

    private void initChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            NotificationChannel tasks = new NotificationChannel(
                    CHANNEL_TASKS, "Antigravity Tasks", NotificationManager.IMPORTANCE_DEFAULT);
            tasks.setDescription("Notifications for ongoing AI agent and environment tasks");
            manager.createNotificationChannel(tasks);

            NotificationChannel alerts = new NotificationChannel(
                    CHANNEL_ALERTS, "Antigravity Alerts", NotificationManager.IMPORTANCE_HIGH);
            alerts.setDescription("High-priority alerts and task completions");
            manager.createNotificationChannel(alerts);
        }
    }

    public JSONObject handle(JSONObject payload) {
        JSONObject resp = new JSONObject();
        try {
            String action = payload.optString("action", "post");
            String tag = payload.optString("tag", "agy_task");
            int id = payload.optInt("id", (int) (System.currentTimeMillis() % 100000));

            if ("cancel".equalsIgnoreCase(action)) {
                if (manager != null) {
                    manager.cancel(tag, id);
                }
                resp.put("ok", true);
                resp.put("cancelled", true);
                return resp;
            }

            String title = payload.optString("title", "Antigravity");
            String message = payload.optString("message", "");
            int progress = payload.optInt("progress", -1);
            boolean ongoing = payload.optBoolean("ongoing", false);
            String channel = payload.optString("channel", CHANNEL_TASKS);

            if (manager == null) {
                resp.put("ok", false);
                resp.put("error", "NotificationManager unavailable");
                return resp;
            }

            int icon = context.getResources().getIdentifier("ic_notification", "drawable", context.getPackageName());
            if (icon == 0) {
                icon = android.R.drawable.ic_dialog_info;
            }

            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(context, channel)
                    : new Notification.Builder(context);

            builder.setContentTitle(title)
                    .setContentText(message)
                    .setSmallIcon(icon)
                    .setOngoing(ongoing)
                    .setAutoCancel(!ongoing);

            if (progress >= 0) {
                builder.setProgress(100, Math.min(100, Math.max(0, progress)), false);
            } else if ("indeterminate".equalsIgnoreCase(payload.optString("progress_mode"))) {
                builder.setProgress(0, 0, true);
            }

            manager.notify(tag, id, builder.build());

            resp.put("ok", true);
            resp.put("tag", tag);
            resp.put("id", id);
        } catch (Exception e) {
            Log.w(TAG, "Notification error: " + e.getMessage());
            try {
                resp.put("ok", false);
                resp.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return resp;
    }
}
