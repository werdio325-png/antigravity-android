package com.agy.install;

import android.util.Log;

import com.agy.util.PathSafety;

import java.io.File;
import java.io.IOException;

/** Deletes Termux profile hooks that would run at shell startup. */
public final class TermuxHooksRemover {

    private static final String TAG = "TermuxHooksRemover";

    private TermuxHooksRemover() {
    }

    public static void remove(File prefix) {
        File profileD = new File(prefix, "etc/profile.d");
        String[] hooks = {
                "01-termux-bootstrap-second-stage-fallback.sh",
                "init-termux-properties.sh"
        };
        for (String hook : hooks) {
            File file = new File(profileD, hook);
            try {
                if (!PathSafety.isInside(prefix, file)) {
                    Log.w(TAG, "refusing to delete outside prefix: " + file);
                    continue;
                }
            } catch (IOException e) {
                Log.w(TAG, "cannot resolve " + file + ": " + e);
                continue;
            }
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "cannot delete profile hook: " + file);
            }
        }
    }
}
