package com.antigravity.mobile.dev;

import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class PerfLogger {
    private static File logFile = null;

    private PerfLogger() {}

    public static void init(File filesDir) {
        logFile = new File(filesDir, "boot_perf.log");
        try {
            if (logFile.exists()) logFile.delete();
            logFile.createNewFile();
        } catch (Exception ignored) {}
    }

    public static synchronized void log(String msg) {
        long elapsed = MainActivity.bootStart > 0 ? (System.currentTimeMillis() - MainActivity.bootStart) : 0;
        String line = String.format("[%5d ms] %s\n", elapsed, msg);
        Log.e("BOOT_PERF", line.trim());
        if (logFile != null) {
            try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
                fos.write(line.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {}
        }
    }
}
