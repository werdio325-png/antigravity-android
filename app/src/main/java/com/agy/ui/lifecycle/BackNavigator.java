package com.agy.ui.lifecycle;

import android.webkit.WebView;

/** Back handling: walk WebView history before finishing the activity. */
public final class BackNavigator {

    private BackNavigator() {
    }

    /** Returns true when the WebView consumed the back press. */
    public static boolean handle(WebView webView) {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return true;
        }
        return false;
    }
}
