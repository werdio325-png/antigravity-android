package com.agy.ui.webview;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.util.Log;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.agy.bridge.AndroidBridge;
import com.agy.oauth.OAuthManager;
import com.agy.util.SensitiveDataRedactor;
import com.agy.util.TrustedCoreOrigin;

/** WebView client: OAuth interception, loopback TLS, and static asset serving. */
public final class AgyWebViewClient extends WebViewClient {

    private static final String TAG = "WebViewHost";

    private final Context context;
    private final OAuthManager oauth;
    private final LocalAssetServer assetServer;
    private final LoopbackSidecarProxy sidecarProxy = new LoopbackSidecarProxy();
    private final AndroidBridge bridge;
    private final WebViewHost.Listener listener;

    public AgyWebViewClient(Context context, OAuthManager oauth, LocalAssetServer assetServer,
                            AndroidBridge bridge, WebViewHost.Listener listener) {
        this.context = context;
        this.oauth = oauth;
        this.assetServer = assetServer;
        this.bridge = bridge;
        this.listener = listener;
    }

    @Override
    public void onReceivedError(WebView v, WebResourceRequest req, WebResourceError err) {
        if (req != null && req.getUrl() != null) {
            Log.w(TAG, "Resource load error: "
                    + SensitiveDataRedactor.safeUrl(req.getUrl().toString()) + " err="
                    + (err != null ? err.getDescription() : "unknown"));
        }
    }

    @Override
    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
        if (request == null || request.getUrl() == null) {
            return true;
        }
        Uri uri = request.getUrl();
        Log.i(TAG, "shouldOverrideUrlLoading url="
                + SensitiveDataRedactor.safeUrl(uri.toString()));
        if (!request.isForMainFrame()) {
            // The web UI intentionally embeds HTTPS previews and loopback HTTP
            // dev-server previews. Keep those working while refusing arbitrary
            // external HTTP, file/javascript/intent, and other non-web subframes.
            String scheme = uri.getScheme();
            if ("https".equalsIgnoreCase(scheme) || "about".equalsIgnoreCase(scheme)) {
                return false;
            }
            if ("http".equalsIgnoreCase(scheme) && isLoopbackHost(uri)) {
                return false;
            }
            return true;
        }
        return handleNavigation(v, uri.toString());
    }

    private static boolean isLoopbackHost(Uri uri) {
        if (uri == null || uri.getUserInfo() != null) {
            return false;
        }
        String host = uri.getHost();
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
    }

    @Override
    @SuppressWarnings("deprecation")
    public boolean shouldOverrideUrlLoading(WebView v, String url) {
        Log.i(TAG, "shouldOverrideUrlLoading(deprecated) url="
                + SensitiveDataRedactor.safeUrl(url));
        return handleNavigation(v, url);
    }

    private boolean handleNavigation(WebView v, String url) {
        if (url == null || url.isEmpty() || url.equals("about:blank")) {
            return false;
        }
        try {
            Uri uri = Uri.parse(url);
            if (!TrustedCoreOrigin.matches(uri, bridge.getEnginePort())) {
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    // Do not open internal backend API endpoints in external browser
                    if (url.contains("googleapis.com/oauth2/v2/userinfo")
                            || url.contains("googleapis.com/auth")) {
                        return true;
                    }
                    Log.i(TAG, "non-core navigation -> external browser: "
                            + SensitiveDataRedactor.safeUrl(url));
                    oauth.openInBrowser(context, url);
                }
                // Never fall through to render a non-core top-level document in
                // the WebView if no browser handler is installed.
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot parse uri: " + SensitiveDataRedactor.safeUrl(url), e);
            return true;
        }
        return oauth.intercept(context, url);
    }

    /**
     * Server-side (3xx) redirects do not always reach shouldOverrideUrlLoading;
     * only the exact core origin may remain in the bridge-enabled WebView.
     */
    @Override
    public void onPageStarted(WebView v, String url, Bitmap favicon) {
        if (url == null || url.isEmpty() || url.equals("about:blank")) {
            return;
        }
        try {
            Uri uri = Uri.parse(url);
            if (!TrustedCoreOrigin.matches(uri, bridge.getEnginePort())) {
                Log.i(TAG, "onPageStarted intercepted non-core url: "
                        + SensitiveDataRedactor.safeUrl(url));
                v.stopLoading();
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    if (!url.contains("googleapis.com/oauth2/v2/userinfo")
                            && !url.contains("googleapis.com/auth")) {
                        oauth.openInBrowser(context, url);
                    }
                }
                return;
            }
        } catch (Exception ignored) {
            v.stopLoading();
            return;
        }
        if (oauth.intercept(context, url)) {
            Log.i(TAG, "onPageStarted stopped auth url="
                    + SensitiveDataRedactor.safeUrl(url));
            v.stopLoading();
        }
    }

    @Override
    public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError error) {
        Log.w(TAG, "rejecting TLS error in WebView: "
                + SensitiveDataRedactor.safeUrl(error != null ? error.getUrl() : null));
        // The embedded core certificate is trusted through network_security_config.
        // Any remaining TLS error is unexpected and must fail closed.
        handler.cancel();
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
        if (request == null || request.getUrl() == null) {
            return null;
        }
        WebResourceResponse asset = assetServer.intercept(context, request.getUrl(), request.getMethod(),
                bridge.getEnginePort());
        if (asset != null) {
            return asset;
        }
        return sidecarProxy.intercept(request, bridge.getEnginePort());
    }

    @Override
    @SuppressWarnings("deprecation")
    public WebResourceResponse shouldInterceptRequest(WebView v, String url) {
        if (url == null) {
            return null;
        }
        try {
            return assetServer.intercept(context, Uri.parse(url), "GET", bridge.getEnginePort());
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void onPageFinished(WebView v, String url) {
        if (listener != null) {
            listener.onPageFinished(url);
        }
    }
}
