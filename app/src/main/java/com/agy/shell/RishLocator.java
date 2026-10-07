package com.agy.shell;

import android.content.Context;

import java.io.File;

/** Locates the bundled rish binary. */
public final class RishLocator {

    private final Context appContext;

    public RishLocator(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** The bundled rish lives next to the core: getFilesDir()/runtime/bin/rish. */
    public File rishBinary() {
        File files = appContext.getFilesDir();
        File[] candidates = new File[]{
                new File(files, "runtime/bin/rish"),
                new File(files, "usr/bin/rish"),
                new File(files, "bin/rish")
        };
        for (File candidate : candidates) {
            if (candidate.exists()) {
                return candidate;
            }
        }
        return candidates[0];
    }
}
