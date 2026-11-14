package dev.continuum.antigravity.ui.webview;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Message;
import android.util.Log;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import dev.continuum.antigravity.oauth.OAuthManager;
import dev.continuum.antigravity.util.SensitiveDataRedactor;

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
        final WebView popup = new WebView(context);
        popup.getSettings().setJavaScriptEnabled(true);
        popup.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, android.webkit.WebResourceRequest request) {
                String url = request != null && request.getUrl() != null
                        ? request.getUrl().toString() : null;
                Log.i(TAG, "popup shouldOverrideUrlLoading url="
                        + SensitiveDataRedactor.safeUrl(url));
                if (url != null && !url.isEmpty() && !url.equals("about:blank")) {
                    oauth.openInBrowser(context, url);
                    try {
                        popup.destroy();
                    } catch (Exception ignored) {
                    }
                    return true;
                }
                return false;
            }

            @Override
            @SuppressWarnings("deprecation")
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                Log.i(TAG, "popup shouldOverrideUrlLoading(deprecated) url="
                        + SensitiveDataRedactor.safeUrl(url));
                if (url != null && !url.isEmpty() && !url.equals("about:blank")) {
                    oauth.openInBrowser(context, url);
                    try {
                        popup.destroy();
                    } catch (Exception ignored) {
                    }
                    return true;
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                if (url != null && !url.isEmpty() && !url.equals("about:blank")) {
                    Log.i(TAG, "popup onPageStarted opened in browser url="
                            + SensitiveDataRedactor.safeUrl(url));
                    v.stopLoading();
                    oauth.openInBrowser(context, url);
                    try {
                        popup.destroy();
                    } catch (Exception ignored) {
                    }
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
