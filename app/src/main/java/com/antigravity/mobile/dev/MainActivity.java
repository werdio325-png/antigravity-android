package com.antigravity.mobile.dev;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

public class MainActivity extends Activity {
    public static long bootStart = 0;
    private WebView webView;
    private View splashView;
    private FrameLayout rootLayout;
    private boolean interfaceLoaded = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private FileChooserHandler fileChooserHandler;
    private WebCacheManager webCacheManager;


    private final Runnable pollServer = new Runnable() {
        @Override
        public void run() {
            if (CoreServerService.serverUrl != null && webView != null) {
                String theme = ThemeManager.isDarkTheme(MainActivity.this) ? "dark" : "light";
                String url = CoreServerService.serverUrl + "&hostTheme=" + theme;
                PerfLogger.log("pollServer: loadUrl " + url);
                webView.loadUrl(url);
                handler.postDelayed(MainActivity.this::dismissSplashWithFade, 12000);
            } else {
                handler.postDelayed(this, 15);
            }
        }
    };

    public static void openCustomTab(android.content.Context context, String url) {
        UrlRouter.openCustomTab(context, url);
    }

    private void dismissSplashWithFade() {
        if (splashView == null || interfaceLoaded) return;
        interfaceLoaded = true;
        PerfLogger.log(">>> dismissSplashWithFade triggered");
        SplashOverlay.dismissWithFade(splashView, rootLayout, () -> splashView = null);
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        ThemeManager.applySystemBarTheme(this, rootLayout, webView);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        bootStart = System.currentTimeMillis();
        PerfLogger.init(getFilesDir());
        PerfLogger.log("1. MainActivity.onCreate started");
        VpnBypassManager.applyBypass(this);
        super.onCreate(savedInstanceState);

        // 1. Kick off Core Service in parallel background thread
        Intent intent = new Intent(this, CoreServerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        PerfLogger.log("2. CoreServerService start initiated");

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        ThemeManager.applySystemBarTheme(this, null, null);

        boolean isDark = ThemeManager.isDarkTheme(this);
        String bg = ThemeManager.getBackgroundColor(isDark);

        rootLayout = new FrameLayout(this);
        setContentView(rootLayout);

        // 2. Main WebView
        webView = new WebView(this);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        rootLayout.addView(webView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 3. Native zero-cost Splash Overlay
        splashView = SplashOverlay.create(this, bg, isDark);
        rootLayout.addView(splashView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ThemeManager.applySystemBarTheme(this, rootLayout, webView);

        // 4. Managers
        fileChooserHandler = new FileChooserHandler();
        webCacheManager = new WebCacheManager(this);
        webCacheManager.prewarm();
        webCacheManager.cleanOldWebCaches();

        // 5. Configure WebSettings
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
            s.setOffscreenPreRaster(true);
        }
        s.setGeolocationEnabled(false);
        s.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Antigravity/" + CoreServerService.ENGINE_VERSION);

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> filePathCallback, FileChooserParams fcp) {
                return fileChooserHandler.onShowFileChooser(MainActivity.this, filePathCallback, fcp);
            }

            @Override
            public boolean onCreateWindow(WebView v, boolean isDialog, boolean isUserGesture, Message msg) {
                WebView temp = new WebView(MainActivity.this);
                temp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                        return UrlRouter.handleUrl(MainActivity.this, req != null && req.getUrl() != null ? req.getUrl().toString() : null);
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
                return UrlRouter.handleUrl(MainActivity.this, req != null && req.getUrl() != null ? req.getUrl().toString() : null);
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                WebResourceResponse resp = webCacheManager.shouldInterceptRequest(view, req);
                return resp != null ? resp : super.shouldInterceptRequest(view, req);
            }

            @Override
            public void onPageCommitVisible(WebView v, String url) {
                PerfLogger.log("onPageCommitVisible: url=" + url);
                if (url != null && CoreServerService.serverUrl != null && url.startsWith(CoreServerService.serverUrl)) {
                    dismissSplashWithFade();
                }
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                PerfLogger.log("onPageFinished: url=" + url);
                if (url != null && url.contains("/auth/callback") && CoreServerService.serverUrl != null) {
                    handler.postDelayed(() -> v.loadUrl(CoreServerService.serverUrl), 500);
                } else if (url != null && CoreServerService.serverUrl != null && url.startsWith(CoreServerService.serverUrl)) {
                    dismissSplashWithFade();
                }
            }
        });

        requestStoragePermissions();
        handler.post(pollServer);
    }

    private void requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (!android.os.Environment.isExternalStorageManager()) {
                    Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            } catch (Exception e) {
                try {
                    Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                } catch (Exception ignored) {}
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, 100);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (fileChooserHandler != null && fileChooserHandler.onActivityResult(requestCode, resultCode, data)) {
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (fileChooserHandler != null) {
            fileChooserHandler.reset();
        }
        if (webView != null) {
            if (rootLayout != null) rootLayout.removeView(webView);
            webView.destroy();
            webView = null;
        }
        if (splashView != null) {
            if (rootLayout != null) rootLayout.removeView(splashView);
            splashView = null;
        }
        super.onDestroy();
    }
}
