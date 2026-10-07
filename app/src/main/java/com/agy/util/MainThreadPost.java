package com.agy.util;

import android.os.Handler;
import android.os.Looper;

/** Posts work to the main looper; used by JS bridges and painters. */
public final class MainThreadPost {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private MainThreadPost() {
    }

    public static void post(Runnable runnable) {
        MAIN.post(runnable);
    }
}
