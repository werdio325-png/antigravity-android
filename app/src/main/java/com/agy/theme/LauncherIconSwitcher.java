package com.agy.theme;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import com.agy.ui.launcher.LauncherDarkActivity;
import com.agy.ui.launcher.LauncherLightActivity;

/**
 * Duolingo-style dynamic launcher icon: enable the launcher activity matching
 * the persisted theme and disable the other. DONT_KILL_APP keeps the running
 * process alive.
 */
public final class LauncherIconSwitcher {

    private LauncherIconSwitcher() {
    }

    public static void apply(Context appContext, boolean dark) {
        try {
            PackageManager pm = appContext.getPackageManager();
            ComponentName darkAlias = new ComponentName(appContext, LauncherDarkActivity.class);
            ComponentName lightAlias = new ComponentName(appContext, LauncherLightActivity.class);
            pm.setComponentEnabledSetting(darkAlias,
                    dark ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                         : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
            pm.setComponentEnabledSetting(lightAlias,
                    dark ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                         : PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) {
        }
    }
}
