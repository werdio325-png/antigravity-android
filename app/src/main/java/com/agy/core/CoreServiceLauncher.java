package com.agy.core;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Starts/stops the foreground core host service. */
public final class CoreServiceLauncher {

    private CoreServiceLauncher() {
    }

    /** May throw if the platform refuses the service start; callers surface it. */
    public static void start(Context context) {
        Intent intent = new Intent(context, CoreServerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        try {
            context.stopService(new Intent(context, CoreServerService.class));
        } catch (Exception ignored) {
        }
    }
}
