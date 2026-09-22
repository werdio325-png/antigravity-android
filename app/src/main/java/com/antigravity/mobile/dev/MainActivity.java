package com.antigravity.mobile.dev;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

public class MainActivity extends Activity implements WebViewController.Listener {
    public static long bootStart = 0;
    private View splashView;
    private FrameLayout rootLayout;
    private WebViewController webViewController;
    private boolean interfaceLoaded = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final Runnable pollServer = new Runnable() {
        @Override
        public void run() {
            if (CoreServerService.serverUrl != null && webViewController != null) {
                String url = CoreServerService.serverUrl;
                PerfLogger.log("pollServer: loadUrl " + url);
                webViewController.loadUrl(url);
                // Safety fallback: dismiss splash after 12s if React never fires
                handler.postDelayed(MainActivity.this::dismissSplashWithFade, 12000);
            } else {
                handler.postDelayed(this, 15);
            }
        }
    };

    public static void openCustomTab(android.content.Context context, String url) {
        UrlRouter.openCustomTab(context, url);
    }

    @Override
    public void onInterfaceRendered() {
        dismissSplashWithFade();
    }

    @Override
    public void onThemeChanged(boolean isDark) {
        PerfLogger.log("MainActivity.onThemeChanged: isDark=" + isDark);
        ThemeManager.applySystemBarTheme(this, rootLayout, webViewController != null ? webViewController.getWebView() : null, isDark);
    }

    private void dismissSplashWithFade() {
        if (splashView == null || interfaceLoaded) return;
        interfaceLoaded = true;
        PerfLogger.log(">>> dismissSplashWithFade triggered");
        if (webViewController != null && webViewController.getWebView() != null) {
            webViewController.getWebView().setVisibility(View.VISIBLE);
            webViewController.getWebView().postInvalidate();
        }
        SplashOverlay.dismissWithFade(splashView, rootLayout, () -> {
            splashView = null;
            if (webViewController != null && webViewController.getWebView() != null) {
                webViewController.getWebView().postInvalidate();
            }
        });
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        ThemeManager.applySystemBarTheme(this, rootLayout, webViewController != null ? webViewController.getWebView() : null);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        bootStart = System.currentTimeMillis();
        PerfLogger.init(getFilesDir());
        PerfLogger.log("1. MainActivity.onCreate started");
        VpnBypassManager.applyBypass(this);
        super.onCreate(savedInstanceState);

        // 1. Start Core Service
        Intent serviceIntent = new Intent(this, CoreServerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        PerfLogger.log("2. CoreServerService start initiated");

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        ThemeManager.applySystemBarTheme(this, null, null);

        boolean isDark = ThemeManager.isDarkTheme(this);
        String bg = ThemeManager.getBackgroundColor(isDark);

        rootLayout = new FrameLayout(this);
        setContentView(rootLayout);

        // 2. WebViewController
        webViewController = new WebViewController(this, bg, this);
        rootLayout.addView(webViewController.getWebView(), new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 3. Splash Overlay
        splashView = SplashOverlay.create(this, bg, isDark);
        rootLayout.addView(splashView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        ThemeManager.applySystemBarTheme(this, rootLayout, webViewController.getWebView());

        // 4. Permissions, server polling, deep links
        PermissionHelper.requestStoragePermissions(this);
        handler.post(pollServer);
        DeepLinkHandler.handleDeepLink(this, webViewController.getWebView(), getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (webViewController != null) {
            DeepLinkHandler.handleDeepLink(this, webViewController.getWebView(), intent);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (webViewController != null && webViewController.getFileChooserHandler() != null &&
            webViewController.getFileChooserHandler().onActivityResult(requestCode, resultCode, data)) {
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webViewController != null) {
            webViewController.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (webViewController != null) {
            webViewController.onPause();
        }
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        if (webViewController != null && webViewController.canGoBack()) {
            webViewController.goBack();
        } else {
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webViewController != null) {
            if (rootLayout != null && webViewController.getWebView() != null) {
                rootLayout.removeView(webViewController.getWebView());
            }
            webViewController.destroy();
            webViewController = null;
        }
        if (splashView != null) {
            if (rootLayout != null) rootLayout.removeView(splashView);
            splashView = null;
        }
        super.onDestroy();
    }
}
