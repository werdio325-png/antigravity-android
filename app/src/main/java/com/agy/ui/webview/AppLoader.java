package com.agy.ui.webview;

import android.content.Context;
import android.util.Log;
import android.webkit.WebView;

import com.agy.bridge.AndroidBridge;
import com.agy.theme.ThemeManager;
import com.agy.util.AssetReader;

/**
 * Loads our asset UI (assets/web/index.html) with the app config injected ahead
 * of every app script, based at the core's https origin so relative assets and
 * ConnectRPC are same-origin.
 */
public final class AppLoader {

    private static final String TAG = "WebViewHost";
    private static final String APP_ASSET = "web/index.html";

    private AppLoader() {
    }

    public static void load(Context context, WebView webView, ThemeManager theme,
                            AndroidBridge bridge) {
        String html = AssetReader.readString(context, APP_ASSET);
        if (html == null) {
            Log.e(TAG, "cannot read " + APP_ASSET + "; UI not loaded");
            return;
        }
        int port = bridge.getEnginePort();
        // Paint the WebView in the persisted theme BEFORE loading so any frame
        // shown under the fading splash matches the in-app background (no
        // white/dark mismatch flash).
        if (theme != null) {
            theme.applyToWebView(webView);
        }
        String hostTheme = theme != null ? theme.getTheme() : "dark";
        String origin = "https://127.0.0.1:" + port + "/";
        String historyUrl = origin + "?hostTheme=" + hostTheme;
        webView.loadDataWithBaseURL(origin, AppHtmlInjector.inject(html, bridge, theme),
                "text/html", "UTF-8", historyUrl);
    }
}
