package com.agy.ui.webview;

import android.net.Uri;
import android.net.http.SslError;
import android.util.Log;

import com.agy.util.LoopbackHost;

/** The core serves a self-signed cert on loopback; proceed only there. */
public final class LoopbackSslPolicy {

    private static final String TAG = "WebViewHost";

    private LoopbackSslPolicy() {
    }

    public static boolean proceed(SslError error) {
        String host = null;
        try {
            String errorUrl = error != null ? error.getUrl() : null;
            if (errorUrl != null) {
                host = Uri.parse(errorUrl).getHost();
            }
        } catch (Exception e) {
            Log.w(TAG, "ssl url parse failed", e);
        }
        return LoopbackHost.isLoopback(host);
    }
}
