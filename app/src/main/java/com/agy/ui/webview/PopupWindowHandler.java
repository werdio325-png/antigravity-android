package com.agy.ui.webview;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Message;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.agy.oauth.OAuthManager;

/**
 * Handles {@code window.open}: the popup is a fresh WebView whose navigations
 * are intercepted for OAuth (Google blocks WebView login).
 */
public final class PopupWindowHandler {

    private static final String TAG = "WebViewHost";

    private final Context context;
    private final OAuthManager oauth;

    public PopupWindowHandler(Context context, OAuthManager oauth) {
        this.context = context;
        this.oauth = oauth;
    }

    public boolean handle(WebView parent, boolean isDialog, boolean isUserGesture,
                          Message resultMsg) {
        Log.i(TAG, "onCreateWindow isDialog=" + isDialog + " isUserGesture=" + isUserGesture);
        WebView popup = new WebView(context);
        popup.getSettings().setJavaScriptEnabled(true);
        popup.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest request) {
                String url = request != null && request.getUrl() != null
                        ? request.getUrl().toString() : null;
                Log.i(TAG, "popup shouldOverrideUrlLoading url=" + url);
                return oauth.intercept(context, url);
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                Log.i(TAG, "popup shouldOverrideUrlLoading(deprecated) url=" + url);
                return oauth.intercept(context, url);
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                if (oauth.intercept(context, url)) {
                    Log.i(TAG, "popup onPageStarted stopped auth url=" + url);
                    v.stopLoading();
                }
            }
        });
        if (resultMsg != null && resultMsg.obj instanceof WebView.WebViewTransport) {
            ((WebView.WebViewTransport) resultMsg.obj).setWebView(popup);
            resultMsg.sendToTarget();
        }
        return true;
    }
}
