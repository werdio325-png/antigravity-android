package com.agy.core;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;

/** Appends core stdout to files/logs/core.log. */
public final class CoreLogWriter {

    private static final String TAG = "CoreLogWriter";

    private CoreLogWriter() {
    }

    public static Writer open(File filesDir) {
        try {
            File dir = new File(filesDir, "logs");
            dir.mkdirs();
            return new OutputStreamWriter(new FileOutputStream(new File(dir, "core.log"), true),
                    Charset.forName("UTF-8"));
        } catch (Exception e) {
            Log.w(TAG, "cannot open core log", e);
            return null;
        }
    }

    public static void writeLine(Writer log, String line) {
        if (log == null || line == null) {
            return;
        }
        try {
            log.write(line);
            log.write('\n');
            log.flush();
        } catch (Exception ignored) {
        }
    }

    public static void closeQuietly(Writer writer) {
        if (writer != null) {
            try {
                writer.close();
            } catch (Exception ignored) {
            }
        }
    }
}
