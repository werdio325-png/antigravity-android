package com.antigravity.mobile.dev;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;
import android.webkit.WebView;

public final class DeepLinkHandler {
    private static final String TAG = "DeepLinkHandler";

    private DeepLinkHandler() {}

    public static boolean handleDeepLink(Activity activity, WebView webView, Intent intent) {
        if (intent == null || intent.getData() == null) {
            return false;
        }
        Uri uri = intent.getData();
        String scheme = uri.getScheme();
        String host = uri.getHost();
        PerfLogger.log("[DeepLink] Received: " + uri);
        Log.i(TAG, "[DeepLink] Received: " + uri);

        if (("antigravity".equals(scheme) || "antigravity-dev".equals(scheme)) && "auth-success".equals(host)) {
            PerfLogger.log("[Auth] Auth-success deep link confirmed! Purging cached auth state and reloading WebView.");
            Log.i(TAG, "[Auth] Auth-success deep link confirmed! Purging cached auth state and reloading WebView.");
            if (webView != null) {
                webView.post(() -> {
                    webView.evaluateJavascript(
                        "(function() {\n" +
                        "  console.log('[Auth] Deep link auth-success received. Purging cached user status and reloading...');\n" +
                        "  try {\n" +
                        "    localStorage.removeItem('jetski.cachedUserStatusJson_v6');\n" +
                        "    localStorage.removeItem('jetski.cachedUserInfoJson_v6');\n" +
                        "  } catch(e) {}\n" +
                        "  if (window.evictJetskiCache) window.evictJetskiCache();\n" +
                        "  setTimeout(function() { location.reload(); }, 200);\n" +
                        "})();", null);
                });
            }
            return true;
        }
        return false;
    }
}
