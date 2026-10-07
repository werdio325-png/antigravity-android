package com.agy.theme;

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
        Context ctx = context;
        if (ctx == null) {
            return false;
        }
        int uiMode = ctx.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return uiMode == Configuration.UI_MODE_NIGHT_YES;
    }
}
