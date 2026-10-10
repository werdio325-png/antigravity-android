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
 * In-place replaces launcher icons when user explicitly switches theme.
 * Finishes the running activity so the launcher updates immediately without dual icons.
 */
public final class LauncherIconSwitcher {

    private LauncherIconSwitcher() {
    }

    public static synchronized void switchInPlaceAndExit(Context context, boolean dark) {
        if (context == null) return;
        try {
            Context appContext = context.getApplicationContext();
            PackageManager pm = appContext.getPackageManager();
            ComponentName target = new ComponentName(appContext,
                    dark ? LauncherDarkActivity.class : LauncherLightActivity.class);
            ComponentName toDisable = new ComponentName(appContext,
                    dark ? LauncherLightActivity.class : LauncherDarkActivity.class);

            boolean targetEnabled = isComponentEffectivelyEnabled(pm, target, dark ? false : true);
            boolean disableEnabled = isComponentEffectivelyEnabled(pm, toDisable, dark ? true : false);

            // If already in target state, nothing to change and no need to exit
            if (targetEnabled && !disableEnabled) {
                return;
            }

            // 1. Immediately enable target launcher icon in place
            pm.setComponentEnabledSetting(target,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP);

            // 2. Immediately disable old launcher icon in place
            pm.setComponentEnabledSetting(toDisable,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);

            // 3. Cleanly finish running activity so the launcher refreshes immediately
            if (context instanceof Activity) {
                final Activity activity = (Activity) context;
                MainThreadPost.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            activity.finishAffinity();
                        } catch (Exception e) {
                            try {
                                activity.finish();
                            } catch (Exception ignored) {
                            }
                        }
                    }
                });
            }
        } catch (Exception ignored) {
        }
    }

    public static synchronized void apply(Context context, boolean dark) {
        switchInPlaceAndExit(context, dark);
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

    /** No-op for lifecycle compatibility. */
    public static synchronized void onAppBackgrounded(Context context) {
    }
}
