package com.agy.theme;

import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;

/** Resolves an explicit or system-following mode to darkness. */
public final class ThemeResolver {

    private ThemeResolver() {
    }

    public static boolean isDark(int mode, Context context) {
        if (mode == ThemeColors.MODE_DARK) {
            return true;
        }
        if (mode == ThemeColors.MODE_LIGHT) {
            return false;
        }
        // MODE_SYSTEM
        if (context == null) {
            return false;
        }

        // 1. Try context Configuration (Activity reflects onConfigurationChanged immediately)
        try {
            int uiMode = context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK;
            if (uiMode == Configuration.UI_MODE_NIGHT_YES) {
                return true;
            }
            if (uiMode == Configuration.UI_MODE_NIGHT_NO) {
                return false;
            }
        } catch (Exception ignored) {
        }

        // 2. Try ApplicationContext Configuration
        try {
            Context appCtx = context.getApplicationContext();
            if (appCtx != null && appCtx != context) {
                int uiMode = appCtx.getResources().getConfiguration().uiMode
                        & Configuration.UI_MODE_NIGHT_MASK;
                if (uiMode == Configuration.UI_MODE_NIGHT_YES) {
                    return true;
                }
                if (uiMode == Configuration.UI_MODE_NIGHT_NO) {
                    return false;
                }
            }
        } catch (Exception ignored) {
        }

        // Fallback: default light
        return false;
    }
}
