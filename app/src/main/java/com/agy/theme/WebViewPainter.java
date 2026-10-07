package com.agy.theme;

import android.webkit.WebView;

/** Paints the WebView background so frames under the splash match the theme. */
public final class WebViewPainter {

    private WebViewPainter() {
    }

    public static void apply(WebView webView, int backgroundColor) {
        if (webView != null) {
            webView.setBackgroundColor(backgroundColor);
        }
    }
}
