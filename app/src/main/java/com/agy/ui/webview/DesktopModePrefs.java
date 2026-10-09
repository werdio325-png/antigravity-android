package com.agy.ui.webview;

import android.content.Context;

/** Persists the user's desktop mode preference. */
public final class DesktopModePrefs {

    private static final String PREFS = "agy_prefs";
    private static final String KEY_DESKTOP_MODE = "desktop_mode";

    private DesktopModePrefs() {
    }

    public static boolean isDesktopMode(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_DESKTOP_MODE, true);
    }

    public static void setDesktopMode(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DESKTOP_MODE, enabled).apply();
    }
}
