package com.antigravity.mobile.dev;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;
import android.view.View;

public final class ThemeManager {

    private ThemeManager() {}

    public static boolean isDarkTheme(Context context) {
        if (context == null) return true;
        int mode = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    public static String getBackgroundColor(boolean isDark) {
        return isDark ? "#101010" : "#ffffff";
    }

    public static void applySystemBarTheme(Activity activity, View rootLayout, View webView) {
        if (activity == null) return;
        applySystemBarTheme(activity, rootLayout, webView, isDarkTheme(activity));
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
    }
}
