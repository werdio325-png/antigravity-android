package com.antigravity.mobile.dev;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;

public final class UrlRouter {

    private UrlRouter() {}

    public static void openCustomTab(Context context, String url) {
        if (url == null || url.trim().isEmpty()) return;
        if (url.contains("accounts.google.com/o/oauth2/auth")) {
            if (url.contains("prompt=consent")) {
                url = url.replace("prompt=consent", "prompt=select_account%20consent");
            } else if (!url.contains("prompt=")) {
                url = url + "&prompt=select_account%20consent";
            }
            PerfLogger.log("[Auth] Opening Google OAuth with forced account chooser: " + url);
            android.util.Log.i("UrlRouter", "[Auth] Opening Google OAuth with forced account chooser: " + url);
        }
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Bundle extras = new Bundle();
            extras.putBinder("android.support.customtabs.extra.SESSION", (IBinder) null);
            intent.putExtras(extras);
            intent.putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", 0xFF101010);
            intent.putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1);
            intent.setPackage("com.android.chrome");
            context.startActivity(intent);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(fallback);
            } catch (Exception ignored) {}
        }
    }

    public static boolean handleUrl(Activity activity, String url) {
        if (url == null || url.isEmpty()) return false;
        if (url.startsWith("https://127.0.0.1") || url.startsWith("http://127.0.0.1") ||
            url.startsWith("https://localhost") || url.startsWith("http://localhost")) {
            return false;
        }
        if (url.startsWith("antigravity://") || url.startsWith("antigravity-dev://")) {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                activity.startActivity(intent);
                return true;
            } catch (Exception ignored) {}
        }
        openCustomTab(activity, url);
        return true;
    }
}
