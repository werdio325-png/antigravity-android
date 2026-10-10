package com.agy.theme;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

import com.agy.ui.launcher.LauncherDarkActivity;
import com.agy.ui.launcher.LauncherLightActivity;

import com.agy.util.MainThreadPost;

/**
 * Dynamic launcher icon switcher.
 *
 * Switches launcher activity-aliases cleanly without killing the running app
 * or kicking the user out. The switch is applied while the app is in the background
 * or smoothly with DONT_KILL_APP so the user's workspace session stays uninterrupted.
 */
public final class LauncherIconSwitcher {

    private static Boolean pendingDark = null;

    private LauncherIconSwitcher() {
    }

    public static synchronized void scheduleSwitch(boolean dark) {
        pendingDark = dark;
    }

    public static synchronized void apply(Context context, boolean dark) {
        if (context == null) return;
        pendingDark = null;
        try {
            Context appContext = context.getApplicationContext();
            PackageManager pm = appContext.getPackageManager();
            ComponentName target = new ComponentName(appContext,
                    dark ? LauncherDarkActivity.class : LauncherLightActivity.class);
            ComponentName toDisable = new ComponentName(appContext,
                    dark ? LauncherLightActivity.class : LauncherDarkActivity.class);

            boolean targetEnabled = isComponentEffectivelyEnabled(pm, target, dark);
            boolean disableEnabled = isComponentEffectivelyEnabled(pm, toDisable, !dark);

            if (targetEnabled && !disableEnabled) {
                return;
            }

            // 1. Enable target launcher activity
            pm.setComponentEnabledSetting(target,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);

            // 2. Disable old launcher activity
            pm.setComponentEnabledSetting(toDisable,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        } catch (Exception ignored) {
        }
    }

    private static boolean isComponentEffectivelyEnabled(PackageManager pm, ComponentName component, boolean defaultInManifest) {
        int state = pm.getComponentEnabledSetting(component);
        if (state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            return true;
        }
        if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
            return false;
        }
        return defaultInManifest;
    }

    /** Called when the app is backgrounded (onStop) to seamlessly apply launcher changes. */
    public static synchronized void onAppBackgrounded(Context context) {
        if (pendingDark != null && context != null) {
            apply(context, pendingDark);
            pendingDark = null;
        }
    }
}
