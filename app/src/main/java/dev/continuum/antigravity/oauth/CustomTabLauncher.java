package dev.continuum.antigravity.oauth;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.util.Log;

import dev.continuum.antigravity.ui.MainActivity;
import dev.continuum.antigravity.util.SensitiveDataRedactor;

/**
 * Opens URLs and OAuth flows in the user's external web browser.
 */
public final class CustomTabLauncher {

    private static final String TAG = OAuthConstants.TAG;

    /**
     * Resolves the user's default web browser package using a neutral HTTPS URL,
     * specifically filtering out Google Play Services and system disambiguation.
     */
    public static String resolveDefaultBrowserPackage(Context context) {
        if (context == null) return null;
        PackageManager pm = context.getPackageManager();
        if (pm == null) return null;

        Intent testIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"));
        testIntent.addCategory(Intent.CATEGORY_BROWSABLE);

        // Only target an explicitly selected default. Do not pick the first
        // arbitrary app that registered an HTTPS intent filter.
        try {
            ResolveInfo defaultResolve = pm.resolveActivity(testIntent, PackageManager.MATCH_DEFAULT_ONLY);
            if (defaultResolve != null && defaultResolve.activityInfo != null) {
                String pkg = defaultResolve.activityInfo.packageName;
                if (isRealBrowserPackage(pkg)) {
                    return pkg;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "resolveActivity failed: " + t.getMessage());
        }
        return null;
    }

    private static boolean isRealBrowserPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        if ("android".equals(pkg)) return false;
        if ("com.android.internal.app.ResolverActivity".equals(pkg)) return false;
        if ("com.android.internal.app.ChooserActivity".equals(pkg)) return false;
        if ("com.google.android.gms".equals(pkg)) return false;
        if ("com.google.android.googlequicksearchbox".equals(pkg)) return false;
        return true;
    }

    public boolean openInBrowser(Context context, String url) {
        if (context == null || url == null || url.trim().isEmpty()) {
            return false;
        }
        final Uri uri;
        try {
            uri = Uri.parse(url.trim());
        } catch (Exception e) {
            Log.e(TAG, "bad url: " + SensitiveDataRedactor.safeUrl(url), e);
            return false;
        }

        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getHost().isEmpty()
                || uri.getUserInfo() != null) {
            Log.w(TAG, "refusing non-web or credential-bearing URL: "
                    + SensitiveDataRedactor.safeUrl(uri.toString()));
            return false;
        }

        final Context target = (MainActivity.activeActivity != null)
                ? MainActivity.activeActivity
                : context;

        return doOpen(target, uri);
    }

    private boolean doOpen(Context context, Uri uri) {
        String defaultPkg = resolveDefaultBrowserPackage(context);

        // 1. Target resolved default browser explicitly if available
        if (defaultPkg != null) {
            Intent view = new Intent(Intent.ACTION_VIEW, uri);
            view.addCategory(Intent.CATEGORY_BROWSABLE);
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            view.setPackage(defaultPkg);
            try {
                context.startActivity(view);
                Log.i(TAG, "opened external browser pkg=" + defaultPkg + " url="
                        + SensitiveDataRedactor.safeUrl(uri.toString()));
                return true;
            } catch (Exception e) {
                Log.w(TAG, "failed to launch default browser pkg=" + defaultPkg + ": " + e.getMessage());
            }
        }

        // No verified default is configured: require an explicit user choice
        // instead of silently launching an arbitrary HTTPS intent handler.
        Intent fallback = new Intent(Intent.ACTION_VIEW, uri);
        fallback.addCategory(Intent.CATEGORY_BROWSABLE);
        fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            Intent chooser = Intent.createChooser(fallback, "Open in browser");
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(chooser);
            Log.i(TAG, "opened chooser for url="
                    + SensitiveDataRedactor.safeUrl(uri.toString()));
            return true;
        } catch (Exception e) {
            Log.e(TAG, "browser chooser launch failed for url: "
                    + SensitiveDataRedactor.safeUrl(uri.toString()), e);
            return false;
        }
    }
}
