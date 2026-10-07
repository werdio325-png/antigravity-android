package com.agy.ui.splash;

import android.app.Activity;
import android.os.Build;
import android.view.View;

/**
 * API31+: intercept the system splash exit so the icon fades/scales out and
 * the 3-dot overlay fades in, instead of a hard cut to the first app frame.
 */
public final class SplashExitInstaller {

    private SplashExitInstaller() {
    }

    public static void install(Activity activity, View dotSplash) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SplashAnim31.install(activity, dotSplash);
        }
    }
}
