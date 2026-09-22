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
                        "  console.log('[Auth] Deep link auth-success received. Starting auth sync...');\n" +
                        "  try {\n" +
                        "    localStorage.removeItem('jetski.cachedUserStatusJson_v6');\n" +
                        "    localStorage.removeItem('jetski.cachedUserInfoJson_v6');\n" +
                        "  } catch(e) {}\n" +
                        "  if (window.evictJetskiCache) window.evictJetskiCache();\n" +
                        "  var attempts = 0;\n" +
                        "  function checkAuth() {\n" +
                        "    attempts++;\n" +
                        "    fetch('/exa.language_server_pb.LanguageServerService/GetUserStatus', {\n" +
                        "      method: 'POST',\n" +
                        "      headers: {'Content-Type': 'application/json'},\n" +
                        "      body: '{}'\n" +
                        "    }).then(function(r) { return r.json(); })\n" +
                        "    .then(function(data) {\n" +
                        "      var email = data && data.userStatus && (data.userStatus.email || data.userStatus.name);\n" +
                        "      if (email && email !== 'unknown') {\n" +
                        "        console.log('[Auth] Confirmed auth for ' + email + ', navigating to /');\n" +
                        "        window.location.href = window.location.origin + '/';\n" +
                        "      } else if (attempts < 20) {\n" +
                        "        setTimeout(checkAuth, 400);\n" +
                        "      } else {\n" +
                        "        console.log('[Auth] Polling timeout reached, reloading...');\n" +
                        "        window.location.href = window.location.origin + '/';\n" +
                        "      }\n" +
                        "    }).catch(function() {\n" +
                        "      if (attempts < 20) setTimeout(checkAuth, 400);\n" +
                        "      else window.location.href = window.location.origin + '/';\n" +
                        "    });\n" +
                        "  }\n" +
                        "  setTimeout(checkAuth, 300);\n" +
                        "})();", null);
                });
            }
            return true;
        }
        return false;
    }
}
