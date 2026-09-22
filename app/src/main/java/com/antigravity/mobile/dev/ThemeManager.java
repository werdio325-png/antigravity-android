package com.antigravity.mobile.dev;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.view.View;

public final class ThemeManager {

    private static final String PREFS_NAME = "antigravity_theme_prefs";
    private static final String KEY_THEME_MODE = "theme_mode"; // "system", "light", "dark"

    private ThemeManager() {}

    public static boolean isDarkTheme(Context context) {
        if (context == null) return true;
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    public static void setThemePreference(Context context, String mode) {
        if (context == null || mode == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_THEME_MODE, mode).apply();
    }

    public static String getThemePreference(Context context) {
        if (context == null) return "system";
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_THEME_MODE, "system");
    }

    public static boolean isEffectiveDark(Context context) {
        String pref = getThemePreference(context);
        if ("light".equalsIgnoreCase(pref)) return false;
        if ("dark".equalsIgnoreCase(pref)) return true;
        return isDarkTheme(context);
    }

    public static String getBackgroundColor(boolean isDark) {
        return isDark ? "#101010" : "#ffffff";
    }

    public static void updateLauncherIcon(Context context, boolean isDark) {
        try {
            PackageManager pm = context.getPackageManager();
            String pkg = context.getPackageName();
            ComponentName darkAlias = new ComponentName(pkg, pkg + ".MainActivityDark");
            ComponentName lightAlias = new ComponentName(pkg, pkg + ".MainActivityLight");

            int darkTarget = isDark ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            int lightTarget = isDark ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;

            if (pm.getComponentEnabledSetting(darkAlias) != darkTarget) {
                pm.setComponentEnabledSetting(darkAlias, darkTarget, PackageManager.DONT_KILL_APP);
            }
            if (pm.getComponentEnabledSetting(lightAlias) != lightTarget) {
                pm.setComponentEnabledSetting(lightAlias, lightTarget, PackageManager.DONT_KILL_APP);
            }
            PerfLogger.log("ThemeManager: launcher icon updated: isDark=" + isDark);
        } catch (Exception e) {
            PerfLogger.log("ThemeManager: failed to update launcher icon: " + e.getMessage());
        }
    }

    public static void updateTaskDescription(Activity activity, boolean isDark) {
        if (activity == null) return;
        try {
            int bgColor = Color.parseColor(getBackgroundColor(isDark));
            activity.setTaskDescription(new ActivityManager.TaskDescription(null, null, bgColor));
        } catch (Exception ignored) {}
    }

    public static void applySystemBarTheme(Activity activity, View rootLayout, View webView) {
        if (activity == null) return;
        applySystemBarTheme(activity, rootLayout, webView, isEffectiveDark(activity));
    }

    @SuppressWarnings("deprecation")
    public static void applySystemBarTheme(Activity activity, View rootLayout, View webView, boolean isDark) {
        if (activity == null) return;
        int bgColor = Color.parseColor(getBackgroundColor(isDark));

        activity.getWindow().setStatusBarColor(bgColor);
        activity.getWindow().setNavigationBarColor(bgColor);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = activity.getWindow().getDecorView().getSystemUiVisibility();
            if (!isDark) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            activity.getWindow().getDecorView().setSystemUiVisibility(flags);
        }

        if (rootLayout != null) rootLayout.setBackgroundColor(bgColor);
        if (webView != null) webView.setBackgroundColor(bgColor);

        updateTaskDescription(activity, isDark);
    }
}
