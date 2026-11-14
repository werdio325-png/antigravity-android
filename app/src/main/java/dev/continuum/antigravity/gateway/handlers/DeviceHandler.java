package dev.continuum.antigravity.gateway.handlers;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;

/**
 * Handles device-level telemetry, haptic feedback, and toast notifications.
 */
public final class DeviceHandler {

    private final Context context;
    private final Handler mainHandler;

    public DeviceHandler(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public JSONObject handleSystemInfo() {
        JSONObject resp = new JSONObject();
        try {
            resp.put("ok", true);

            // Battery
            Intent batteryIntent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (batteryIntent != null) {
                int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                int status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                boolean charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
                resp.put("battery", scale > 0 ? (int) ((level / (float) scale) * 100) : level);
                resp.put("charging", charging);
            }

            // Network
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                NetworkInfo net = cm.getActiveNetworkInfo();
                if (net != null && net.isConnected()) {
                    resp.put("network", net.getTypeName().toLowerCase());
                } else {
                    resp.put("network", "offline");
                }
            }

            // Storage
            File filesDir = context.getFilesDir();
            resp.put("storage_free_mb", filesDir.getUsableSpace() / (1024 * 1024));

            // Memory
            Runtime rt = Runtime.getRuntime();
            resp.put("memory_free_mb", (rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())) / (1024 * 1024));
        } catch (Exception e) {
            try {
                resp.put("ok", false);
                resp.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return resp;
    }

    public JSONObject handleVibrate(JSONObject payload) {
        JSONObject resp = new JSONObject();
        try {
            Vibrator vibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                long duration = payload.optLong("duration_ms", 100);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(duration);
                }
                resp.put("ok", true);
            } else {
                resp.put("ok", false);
                resp.put("error", "vibrator not available");
            }
        } catch (Exception e) {
            try {
                resp.put("ok", false);
                resp.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return resp;
    }

    public JSONObject handleToast(JSONObject payload) {
        JSONObject resp = new JSONObject();
        try {
            String message = payload.optString("message", "");
            boolean isLong = "long".equalsIgnoreCase(payload.optString("duration"));
            mainHandler.post(() -> Toast.makeText(context, message, isLong ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show());
            resp.put("ok", true);
        } catch (Exception e) {
            try {
                resp.put("ok", false);
                resp.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return resp;
    }
}
