package dev.continuum.antigravity.ui.webview;

import android.content.Context;
import android.webkit.WebView;

import dev.continuum.antigravity.bridge.AndroidBridge;
import dev.continuum.antigravity.oauth.OAuthManager;
import dev.continuum.antigravity.theme.ThemeManager;
import dev.continuum.antigravity.update.UpdateBridge;
import dev.continuum.antigravity.update.UpdateManager;
import dev.continuum.antigravity.util.CoreUrl;

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
    private final UpdateManager updateManager;
    private final UpdateBridge updateBridge;
    private Listener listener;

    public WebViewHost(Context context, ThemeManager theme, OAuthManager oauth,
                       AndroidBridge bridge, AgyWebChromeClient.FileChooserCallback fileChooserCallback) {
        this.context = context;
        this.theme = theme;
        this.oauth = oauth;
        this.bridge = bridge;
        this.webView = WebViewFactory.create(context, bridge, theme);
        // In-app updater: state is pushed back into the page as JSON events so
        // the UI can show real progress, changelog and checksum status.
        this.updateManager = new UpdateManager(context, new UpdateManager.Sink() {
            @Override
            public void emit(String json) {
                final String script = json;
                WebViewHost.this.webView.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            WebViewHost.this.webView.evaluateJavascript(script, null);
                        } catch (Exception ignored) {
                        }
                    }
                });
            }
        });
        this.updateBridge = new UpdateBridge(updateManager, bridge.getBlobBridgeToken());
        this.webView.addJavascriptInterface(updateBridge, "AndroidUpdate");
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
        this.webView.setDownloadListener(new android.webkit.DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimetype, long contentLength) {
                if (dev.continuum.antigravity.ui.MainActivity.activeActivity != null) {
                    dev.continuum.antigravity.ui.download.FileDownloader.handleDownload(
                            dev.continuum.antigravity.ui.MainActivity.activeActivity, webView, url,
                            userAgent, contentDisposition, mimetype, contentLength);
                }
            }
        });
    }

    public WebViewHost(Context context, ThemeManager theme, OAuthManager oauth,
                       AndroidBridge bridge) {
        this(context, theme, oauth, bridge, null);
    }

    /** Stops the updater worker when the host activity goes away. */
    public void shutdown() {
        updateManager.shutdown();
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
