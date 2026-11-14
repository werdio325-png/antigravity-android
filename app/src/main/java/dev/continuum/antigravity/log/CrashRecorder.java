package dev.continuum.antigravity.log;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Captures, formats, and persists crash telemetry and diagnostics reports.
 */
public final class CrashRecorder {

    private static final String TAG = "CrashRecorder";
    private static volatile JSONObject latestCrash = null;

    private CrashRecorder() {
    }

    public static synchronized JSONObject recordCrash(Context context, int exitCode, long uptimeMs,
                                                       String errorDescription, LogRingBuffer logBuffer) {
        JSONObject report = new JSONObject();
        try {
            long now = System.currentTimeMillis();
            report.put("timestamp", now);
            report.put("exit_code", exitCode);
            report.put("uptime_ms", uptimeMs);
            report.put("error", LogRedactor.redact(errorDescription));

            // Runtime metrics
            Runtime rt = Runtime.getRuntime();
            JSONObject mem = new JSONObject();
            mem.put("free_mb", rt.freeMemory() / (1024 * 1024));
            mem.put("total_mb", rt.totalMemory() / (1024 * 1024));
            mem.put("max_mb", rt.maxMemory() / (1024 * 1024));
            report.put("memory", mem);

            // Disk metrics
            File filesDir = context.getFilesDir();
            JSONObject disk = new JSONObject();
            disk.put("usable_mb", filesDir.getUsableSpace() / (1024 * 1024));
            disk.put("total_mb", filesDir.getTotalSpace() / (1024 * 1024));
            report.put("storage", disk);

            // Recent log tail
            if (logBuffer != null) {
                List<LogRingBuffer.LogEntry> recent = logBuffer.getRecent(50);
                JSONArray logArray = new JSONArray();
                for (LogRingBuffer.LogEntry entry : recent) {
                    logArray.put(LogRedactor.redact(entry.text));
                }
                report.put("log_tail", logArray);
            }

            latestCrash = report;
            persistReport(context, report, now);
        } catch (Exception e) {
            Log.w(TAG, "failed to record crash: " + e.getMessage());
        }
        return report;
    }

    public static JSONObject getLatestCrash() {
        return latestCrash;
    }

    private static void persistReport(Context context, JSONObject report, long timestamp) {
        try {
            File crashesDir = new File(context.getFilesDir(), "logs/crashes");
            if (!crashesDir.exists()) {
                crashesDir.mkdirs();
            }
            File crashFile = new File(crashesDir, "crash_" + timestamp + ".json");
            try (FileOutputStream fos = new FileOutputStream(crashFile)) {
                fos.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            Log.i(TAG, "Crash report persisted: " + crashFile.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "failed to persist crash report: " + e.getMessage());
        }
    }
}
