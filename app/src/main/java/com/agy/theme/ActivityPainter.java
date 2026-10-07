package com.agy.theme;

import android.app.Activity;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

/** Paints the activity window (bars, background, system-ui flags). */
public final class ActivityPainter {

    private ActivityPainter() {
    }

    public static void apply(Activity activity, boolean dark) {
        if (activity == null) {
            return;
        }
        int bg = dark ? ThemeColors.COLOR_DARK : ThemeColors.COLOR_LIGHT;
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(bg);
        window.setNavigationBarColor(bg);
        // Window background comes from a day/night resource (system-resolved);
        // override it so a forced in-app mode never flashes the other theme.
        window.setBackgroundDrawable(new ColorDrawable(bg));
        window.getDecorView().setBackgroundColor(bg);

        int flags = window.getDecorView().getSystemUiVisibility();
        if (dark) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        window.getDecorView().setSystemUiVisibility(flags);
    }
}
