package com.antigravity.mobile.dev;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class WebViewController {
    private static final String TAG = "WebViewController";

    public interface Listener {
        void onInterfaceRendered();
        void onThemeChanged(boolean isDark);
        void onThemeModeSelected(String mode);
    }

    private final Activity activity;
    private final Listener listener;
    private final Handler handler;
    private WebView webView;
    private FileChooserHandler fileChooserHandler;
    private WebCacheManager webCacheManager;

    public WebViewController(Activity activity, String bgColor, Listener listener) {
        this.activity = activity;
        this.listener = listener;
        this.handler = new Handler(Looper.getMainLooper());
        init(bgColor);
    }

    private void init(String bgColor) {
        webView = new WebView(activity);
        webView.setBackgroundColor(Color.parseColor(bgColor));

        fileChooserHandler = new FileChooserHandler();
        webCacheManager = new WebCacheManager(activity);
        webCacheManager.prewarm();
        webCacheManager.cleanOldWebCaches();

        configureSettings();
        setupClients();
    }

    private void configureSettings() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            s.setSafeBrowsingEnabled(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            s.setOffscreenPreRaster(false);
        }
        s.setGeolocationEnabled(false);
        s.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Antigravity/" + CoreServerService.ENGINE_VERSION);

        webView.setWebContentsDebuggingEnabled(true);
    }

    private void setupClients() {
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void onInterfaceRendered() {
                PerfLogger.log(">>> Native bridge: onInterfaceRendered received from React DOM!");
                notifyInterfaceRendered();
            }

            @JavascriptInterface
            public void onThemeChanged(boolean isDark) {
                PerfLogger.log(">>> Native bridge: onThemeChanged: isDark=" + isDark);
                if (listener != null) {
                    handler.post(() -> listener.onThemeChanged(isDark));
                }
            }

            @JavascriptInterface
            public boolean isSystemDark() {
                return ThemeManager.isDarkTheme(activity);
            }

            @JavascriptInterface
            public String getSavedThemeMode() {
                return ThemeManager.getThemePreference(activity);
            }

            @JavascriptInterface
            public void onThemeModeSelected(String mode) {
                PerfLogger.log(">>> Native bridge: onThemeModeSelected: mode=" + mode);
                if (listener != null) {
                    handler.post(() -> listener.onThemeModeSelected(mode));
                }
            }

            @JavascriptInterface
            public void log(String tag, String msg) {
                PerfLogger.log("BRIDGE [" + tag + "] " + msg);
            }
        }, "AntigravityNative");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                if (cm != null && cm.message() != null) {
                    String msg = cm.message();
                    PerfLogger.log("JS: " + msg);
                    Log.i("Antigravity_JS", msg);
                    if (msg.contains("Input area rendered") || msg.contains("React UI fully mounted")) {
                        notifyInterfaceRendered();
                    }
                }
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> filePathCallback, FileChooserParams fcp) {
                return fileChooserHandler.onShowFileChooser(activity, filePathCallback, fcp);
            }

            @Override
            public boolean onCreateWindow(WebView v, boolean isDialog, boolean isUserGesture, Message msg) {
                WebView temp = new WebView(activity);
                temp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                        return UrlRouter.handleUrl(activity, req != null && req.getUrl() != null ? req.getUrl().toString() : null);
                    }
                });
                ((WebView.WebViewTransport) msg.obj).setWebView(temp);
                msg.sendToTarget();
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap favicon) {
                PerfLogger.log("onPageStarted: url=" + url);
            }

            @Override
            public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) {
                PerfLogger.log("onReceivedSslError for " + (e != null ? e.getUrl() : "null"));
                h.proceed(); // Trust local loopback TLS
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return UrlRouter.handleUrl(activity, req != null && req.getUrl() != null ? req.getUrl().toString() : null);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                WebResourceResponse resp = webCacheManager.shouldInterceptRequest(view, req);
                return resp != null ? resp : super.shouldInterceptRequest(view, req);
            }

            @Override
            public void onPageCommitVisible(WebView v, String url) {
                PerfLogger.log("onPageCommitVisible: url=" + url);
                // Intentionally do NOT dismiss splash here: DOM is still empty skeleton #101010.
                // Splash dismissal is triggered when React UI signals onInterfaceRendered.
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                PerfLogger.log("onPageFinished: url=" + url);
                if (url != null && url.contains("/auth/callback") && CoreServerService.serverUrl != null) {
                    handler.postDelayed(() -> v.loadUrl(CoreServerService.serverUrl), 500);
                }
                // Intentionally do NOT dismiss splash here: wait for React DOM to mount.
            }
        });
    }

    private void notifyInterfaceRendered() {
        if (listener != null) {
            handler.post(listener::onInterfaceRendered);
        }
    }

    public WebView getWebView() {
        return webView;
    }

    public FileChooserHandler getFileChooserHandler() {
        return fileChooserHandler;
    }

    public void loadUrl(String url) {
        if (webView != null) {
            webView.loadUrl(url);
        }
    }

    public void evaluateJavascript(String script, ValueCallback<String> resultCallback) {
        if (webView != null) {
            webView.evaluateJavascript(script, resultCallback);
        }
    }

    public void onResume() {
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
            webView.postInvalidate();
        }
    }

    public void onPause() {
        if (webView != null) {
            webView.onPause();
            webView.pauseTimers();
        }
    }

    public boolean canGoBack() {
        return webView != null && webView.canGoBack();
    }

    public void goBack() {
        if (webView != null) {
            webView.goBack();
        }
    }

    public void destroy() {
        if (fileChooserHandler != null) {
            fileChooserHandler.reset();
        }
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
    }
}
