package dev.continuum.antigravity.update;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;

import java.io.File;

import dev.continuum.antigravity.util.AppFileProvider;

/**
 * Installs an update through Android's package installer.
 *
 * There is deliberately no silent-install path: the system installer shows its
 * own consent screen, and the user can cancel. The APK has already been hashed
 * against the release's published checksum before reaching this class.
 */
public final class UpdateInstaller {

    private static final String TAG = "UpdateInstaller";
    private static final String APK_MIME = "application/vnd.android.package-archive";

    private UpdateInstaller() {
    }

    /** True when the user must first allow this app to install packages. */
    public static boolean needsInstallPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return !context.getPackageManager().canRequestPackageInstalls();
        }
        return false;
    }

    /** Opens the per-app "install unknown apps" settings page. */
    public static void openInstallPermissionSettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + context.getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            Log.w(TAG, "cannot open install-permission settings: " + e.getMessage());
        }
    }

    /**
     * Launches the system installer for a verified APK.
     *
     * @return true when the installer activity was started.
     */
    public static boolean install(Context context, File apk) {
        if (apk == null || !apk.isFile()) {
            Log.w(TAG, "install requested without a downloaded APK");
            return false;
        }
        try {
            Uri uri = AppFileProvider.getUriForFile(context, apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, APK_MIME);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            Log.i(TAG, "package installer launched for " + apk.getName());
            return true;
        } catch (Exception e) {
            Log.w(TAG, "cannot launch package installer: " + e.getMessage());
            return false;
        }
    }
}
