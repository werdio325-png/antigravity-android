package com.agy.util;

import android.content.pm.PackageManager;

/** Package visibility queries. */
public final class PackageInspector {

    private PackageInspector() {
    }

    public static boolean isPackageInstalled(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
