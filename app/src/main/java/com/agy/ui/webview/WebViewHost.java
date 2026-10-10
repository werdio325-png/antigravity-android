package com.agy.ui.webview;

import android.content.Context;
import android.webkit.WebView;

import com.agy.bridge.AndroidBridge;
import com.agy.oauth.OAuthManager;
import com.agy.theme.ThemeManager;
import com.agy.util.CoreUrl;

/**
 * Builds and configures the single WebView that hosts *our* patched UI
 * (assets/web/index.html). The HTML is injected inline but based at the core's
 * https origin. The native object is exposed as `Android`.
 */
public final class WebViewHost {

    public static final String DESKTOP_UA = WebSettingsFactory.DESKTOP_UA;

    public interface Listener {
        void onPageFinished(String url);
    }

    private final Context context;
    private final ThemeManager theme;
    private final OAuthManager oauth;
    private final AndroidBridge bridge;
    private final WebView webView;
    private Listener listener;

    public WebViewHost(Context context, ThemeManager theme, OAuthManager oauth,
                       AndroidBridge bridge, AgyWebChromeClient.FileChooserCallback fileChooserCallback) {
        this.context = context;
        this.theme = theme;
        this.oauth = oauth;
        this.bridge = bridge;
        this.webView = WebViewFactory.create(context, bridge, theme);
        this.webView.setWebChromeClient(new AgyWebChromeClient(
                new PopupWindowHandler(context, oauth), fileChooserCallback));
        this.webView.setWebViewClient(new AgyWebViewClient(
                context, oauth, new LocalAssetServer(), bridge, new Listener() {
            @Override
            public void onPageFinished(String url) {
                if (WebViewHost.this.listener != null) {
                    WebViewHost.this.listener.onPageFinished(url);
                }
            }
        }));
    }

    public WebViewHost(Context context, ThemeManager theme, OAuthManager oauth,
                       AndroidBridge bridge) {
        this(context, theme, oauth, bridge, null);
    }

    public WebView getWebView() {
        return webView;
    }

    /** Releases WebView renderer caches; safe to call repeatedly. */
    public void freeMemory() {
        WebViewLifecycle.freeMemory(webView);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public static String startUrl(int port) {
        return CoreUrl.startUrl(port);
    }

    public static String startUrl(int port, String csrfToken) {
        return CoreUrl.startUrl(port, csrfToken);
    }

    public boolean isDesktopMode() {
        return DesktopModePrefs.isDesktopMode(context);
    }

    public void setDesktopMode(boolean enabled) {
        DesktopModePrefs.setDesktopMode(context, enabled);
        webView.post(new Runnable() {
            @Override
            public void run() {
                WebSettingsFactory.applyDesktopMode(webView, enabled);
                webView.reload();
            }
        });
    }

    /** Loads our asset UI with the app config injected ahead of every app script. */
    public void loadApp() {
        AppLoader.load(context, webView, theme, bridge);
    }
}
