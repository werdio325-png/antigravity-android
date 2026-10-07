package com.agy.install;

import android.util.Log;

import com.agy.util.StreamDrain;

import java.io.File;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/** Runs the system chmod to set a unix mode on an extracted file. */
public final class ChmodRunner {

    private static final String TAG = "ChmodRunner";

    private ChmodRunner() {
    }

    public static void rawChmod(File file, String mode) {
        if (!new File(EnvPaths.CHMOD).isFile()) {
            return;
        }
        try {
            Process p = new ProcessBuilder(EnvPaths.CHMOD, mode, file.getAbsolutePath())
                    .redirectErrorStream(true).start();
            try (InputStream in = p.getInputStream()) {
                StreamDrain.drain(in);
            }
            p.waitFor(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.w(TAG, "chmod failed " + file + ": " + e);
        }
    }
}
