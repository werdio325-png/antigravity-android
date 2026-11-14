package dev.continuum.antigravity.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.widget.FrameLayout;

import java.util.ArrayList;
import java.util.List;

import dev.continuum.antigravity.bridge.AndroidBridge;
import dev.continuum.antigravity.core.CoreRuntimeState;
import dev.continuum.antigravity.core.CoreServiceLauncher;
import dev.continuum.antigravity.oauth.OAuthManager;
import dev.continuum.antigravity.oauth.OAuthTokenStore;
import dev.continuum.antigravity.runtime.RuntimeManager;
import dev.continuum.antigravity.shell.ShizukuBridge;
import dev.continuum.antigravity.theme.ThemeManager;
import dev.continuum.antigravity.ui.lifecycle.AppLifecycle;
import dev.continuum.antigravity.ui.lifecycle.BackNavigator;
import dev.continuum.antigravity.ui.lifecycle.ThemeRefresher;
import dev.continuum.antigravity.ui.splash.SplashController;
import dev.continuum.antigravity.ui.webview.AgyWebChromeClient;
import dev.continuum.antigravity.ui.webview.DesktopModePrefs;
import dev.continuum.antigravity.ui.webview.WebViewHost;
import dev.continuum.antigravity.util.SensitiveDataRedactor;
import dev.continuum.antigravity.util.ViewParams;

/**
 * Activity host: builds the WebView, starts CoreServerService, shows the
 * three-dot splash until the core UI has rendered, and wires theming/OAuth.
 * All logic lives in the helper classes; this only wires them and polls.
 */
public class MainActivity extends Activity
        implements WebViewHost.Listener, AgyWebChromeClient.FileChooserCallback {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_STORAGE = 100;
    private static final int REQUEST_FILE_CHOOSER = 1002;
    private static final long SPLASH_TIMEOUT_MS = 60000;
    private static final long POLL_STARTUP_INTERVAL_MS = 50;
    private static final long POLL_READY_INTERVAL_MS = 1000;

    public static volatile MainActivity activeActivity;

    private ValueCallback<Uri[]> filePathCallback;

    private ThemeManager themeManager;
    private OAuthManager oauthManager;
    private RuntimeManager runtimeManager;
    private ShizukuBridge shizukuBridge;
    private AndroidBridge androidBridge;
    private WebViewHost webViewHost;
    private SplashController splash;
    private FrameLayout rootLayout;

    private boolean appLoaded = false;
    private boolean activityResumed = false;
    private int loadedGeneration = -1;
    private long splashDeadline;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /**
     * Runs for the whole activity life: it drives the splash, loads the app on
     * ready, and keeps watching for a post-ready core death.
     */
    private final Runnable pollServer = new Runnable() {
        @Override
        public void run() {
            watchCore();
            if (activityResumed) {
                handler.postDelayed(this, appLoaded
                        ? POLL_READY_INTERVAL_MS : POLL_STARTUP_INTERVAL_MS);
            }
        }
    };

    private void watchCore() {
        String error = CoreRuntimeState.lastError;
        if (error != null) {
            if (CoreRuntimeState.restarting) {
                splash.showTransient(error);
            } else {
                splash.showFatalError(error);
            }
            return;
        }
        if (CoreRuntimeState.serverUrl != null && webViewHost != null) {
            int generation = CoreRuntimeState.readyGeneration;
            if (!appLoaded || (loadedGeneration > 0 && generation != loadedGeneration)) {
                appLoaded = true;
                loadedGeneration = generation;
                splash.clearFatal();
                if (generation > 1) {
                    splash.showLoading();
                }
                webViewHost.loadApp();
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (appLoaded && !splash.isFatalShown()) {
                            Log.w(TAG, "watchdog: auto-dismissing splash after timeout");
                            dismissSplash();
                        }
                    }
                }, 10000);
            }
            return;
        }
        if (!appLoaded && CoreRuntimeState.serverUrl == null && !splash.isFatalShown()
                && System.currentTimeMillis() > splashDeadline) {
            splash.showFatalError("core did not start within " + (SPLASH_TIMEOUT_MS / 1000) + "s");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activeActivity = this;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

        themeManager = new ThemeManager(this);
        themeManager.applyToActivity(this);


        oauthManager = new OAuthManager();
        runtimeManager = new RuntimeManager(this);
        shizukuBridge = new ShizukuBridge(this);
        androidBridge = new AndroidBridge(this, runtimeManager, shizukuBridge, themeManager);
        androidBridge.setActivity(this);
        webViewHost = new WebViewHost(this, themeManager, oauthManager, androidBridge, this);
        webViewHost.setListener(this);

        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(themeManager.getBackgroundColor());
        setContentView(rootLayout);

        rootLayout.addView(webViewHost.getWebView(), ViewParams.full());

        splash = new SplashController(this, themeManager, rootLayout);
        splash.setRetryListener(this::retryCore);
        rootLayout.addView(splash.view(), ViewParams.full());
        splash.loadInitial();

        splash.installExitAnimation(this);

        splashDeadline = System.currentTimeMillis() + SPLASH_TIMEOUT_MS;
        requestStoragePermissions();

        startCoreService();
    }

    private void startCoreService() {
        try {
            CoreServiceLauncher.start(this);
        } catch (Exception e) {
            showFatalError("cannot start core service: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        }
    }

    /** Keep the splash visible and replace it with a readable failure message. */
    public void showFatalError(String message) {
        splash.showFatalError(message);
    }

    /** Retry re-runs install + start: stop the service, then start it again. */
    private void retryCore() {
        splash.clearFatal();
        appLoaded = false;
        loadedGeneration = -1;
        splashDeadline = System.currentTimeMillis() + SPLASH_TIMEOUT_MS;
        splash.showLoading();
        CoreServiceLauncher.stop(this);
        try {
            java.io.File marker = new java.io.File(runtimeManager.getRuntimeDir(), dev.continuum.antigravity.runtime.RuntimePaths.RUNTIME_MARKER);
            if (marker.exists()) {
                marker.delete();
            }
        } catch (Exception ignored) {
        }
        handler.postDelayed(this::startCoreService, 400);
    }

    /**
     * Page load finished, but the Web UI has NOT necessarily painted yet (the
     * app bundle is large), so the splash is NOT dismissed here. Dismissal is
     * driven by web/patches/startup_ready.js -> AndroidBridge.onUiReady().
     */
    @Override
    public void onPageFinished(String url) {
        Log.i(TAG, "onPageFinished " + SensitiveDataRedactor.safeUrl(url));
    }

    /** Idempotent; called by AndroidBridge.onUiReady() on the main thread. */
    public void dismissSplash() {
        Log.i(TAG, "dismissSplash: fading out splashView (280ms)");
        splash.dismiss();
    }

    public boolean isDesktopMode() {
        return webViewHost != null ? webViewHost.isDesktopMode() : DesktopModePrefs.isDesktopMode(this);
    }

    public void setDesktopMode(boolean enabled) {
        if (webViewHost != null) {
            webViewHost.setDesktopMode(enabled);
        } else {
            DesktopModePrefs.setDesktopMode(this, enabled);
        }
    }

    @Override
    protected void onDestroy() {
        activityResumed = false;
        if (activeActivity == this) {
            activeActivity = null;
        }
        AppLifecycle.destroy(handler);
        if (androidBridge != null) {
            androidBridge.setActivity(null);
        }
        if (rootLayout != null) {
            rootLayout.removeAllViews();
        }
        if (webViewHost != null) {
            webViewHost.shutdown();
            WebView wv = webViewHost.getWebView();
            if (wv != null) {
                try {
                    wv.stopLoading();
                    wv.setWebChromeClient(null);
                    wv.setWebViewClient(null);
                    wv.setDownloadListener(null);
                    wv.destroy();
                } catch (Exception e) {
                    Log.w(TAG, "error destroying webView", e);
                }
            }
        }
        super.onDestroy();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        AppLifecycle.trimMemory(level, handler, webViewHost);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        refreshTheme();
    }

    @Override
    protected void onPause() {
        activityResumed = false;
        handler.removeCallbacks(pollServer);
        if (webViewHost != null) {
            AppLifecycle.pause(webViewHost.getWebView());
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        dev.continuum.antigravity.theme.LauncherIconSwitcher.onAppBackgrounded(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        activityResumed = true;
        activeActivity = this;
        if (webViewHost != null) {
            AppLifecycle.resume(webViewHost.getWebView());
        }
        refreshTheme();
        handler.removeCallbacks(pollServer);
        handler.post(pollServer);
    }

    private void refreshTheme() {
        ThemeRefresher.refresh(this, themeManager, rootLayout, splash, webViewHost);
        if (themeManager != null) {
            dev.continuum.antigravity.theme.LauncherIconSwitcher.scheduleSwitch(themeManager.isDark());
        }
        if (webViewHost != null && webViewHost.getWebView() != null) {
            String resolved = themeManager != null ? themeManager.getTheme() : "light";
            webViewHost.getWebView().evaluateJavascript(
                    "window.__AG_RESOLVED_THEME__='" + resolved + "'; window.__agOnSystemThemeChanged && window.__agOnSystemThemeChanged();", null);
        }
    }

    @Override
    public void onBackPressed() {
        if (!BackNavigator.handle(webViewHost != null ? webViewHost.getWebView() : null)) {
            super.onBackPressed();
        }
    }

    private void requestStoragePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (!android.os.Environment.isExternalStorageManager()) {
                    Intent intent = new Intent(
                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                }
            } catch (Exception e) {
                try {
                    startActivity(new Intent(
                            android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (Exception ignored) {
                }
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{
                        android.Manifest.permission.READ_EXTERNAL_STORAGE,
                        android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                }, REQUEST_STORAGE);
            }
        }
    }

    @Override
    public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                      WebChromeClient.FileChooserParams fileChooserParams) {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        this.filePathCallback = callback;

        Intent chooserIntent = null;

        // 1. Try standard intent built by Chromium WebChromeClient
        if (fileChooserParams != null) {
            try {
                Intent defaultIntent = fileChooserParams.createIntent();
                if (defaultIntent != null) {
                    chooserIntent = Intent.createChooser(defaultIntent, "Select Media / Files");
                }
            } catch (Throwable t) {
                Log.w(TAG, "fileChooserParams.createIntent failed: " + t.getMessage());
            }
        }

        // 2. Fallback to universal ACTION_GET_CONTENT chooser (supports Gallery, Photos, Files)
        if (chooserIntent == null) {
            Intent contentIntent = new Intent(Intent.ACTION_GET_CONTENT);
            contentIntent.addCategory(Intent.CATEGORY_OPENABLE);
            contentIntent.setType("*/*");
            if (fileChooserParams != null) {
                String[] acceptTypes = fileChooserParams.getAcceptTypes();
                if (acceptTypes != null && acceptTypes.length > 0) {
                    List<String> validMimes = new ArrayList<>();
                    for (String type : acceptTypes) {
                        if (type != null && type.contains("/")) {
                            validMimes.add(type.trim());
                        }
                    }
                    if (!validMimes.isEmpty()) {
                        contentIntent.putExtra(Intent.EXTRA_MIME_TYPES, validMimes.toArray(new String[0]));
                    }
                }
                if (fileChooserParams.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                    contentIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
            }
            chooserIntent = Intent.createChooser(contentIntent, "Select Media / Files");
        }

        try {
            startActivityForResult(chooserIntent, REQUEST_FILE_CHOOSER);
            return true;
        } catch (Throwable e) {
            Log.w(TAG, "Chooser failed, fallback to plain GET_CONTENT: " + e.getMessage());
            try {
                Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
                fallback.addCategory(Intent.CATEGORY_OPENABLE);
                fallback.setType("*/*");
                if (fileChooserParams != null
                        && fileChooserParams.getMode() == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                    fallback.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                startActivityForResult(fallback, REQUEST_FILE_CHOOSER);
                return true;
            } catch (Throwable ex) {
                Log.e(TAG, "Cannot launch file chooser: " + ex.getMessage());
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                    filePathCallback = null;
                }
                return false;
            }
        }
    }

    public static final int REQUEST_DIR_CHOOSER = 2002;

    public void launchDirectoryPicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(intent, REQUEST_DIR_CHOOSER);
        } catch (Throwable t) {
            Log.w(TAG, "Cannot launch directory picker: " + t.getMessage());
            if (webViewHost != null && webViewHost.getWebView() != null) {
                webViewHost.getWebView().evaluateJavascript("window.__onDirectoryPicked && window.__onDirectoryPicked(null);", null);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_DIR_CHOOSER) {
            String realPath = null;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                Uri treeUri = data.getData();
                try {
                    grantUriPermission(getPackageName(), treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) {}
                try {
                    getContentResolver().takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Throwable ignored) {}
                realPath = dev.continuum.antigravity.util.DocumentsContractHelper.resolveTreeUriToPath(this, treeUri);
            }
            if (webViewHost != null && webViewHost.getWebView() != null) {
                final String path = realPath;
                webViewHost.getWebView().post(new Runnable() {
                    @Override
                    public void run() {
                        String jsArg = path != null ? org.json.JSONObject.quote(path) : "null";
                        webViewHost.getWebView().evaluateJavascript(
                                "window.__onDirectoryPicked && window.__onDirectoryPicked(" + jsArg + ");", null);
                    }
                });
            }
            return;
        }
        if (requestCode == REQUEST_FILE_CHOOSER) {
            if (filePathCallback != null) {
                Uri[] results = null;
                try {
                    results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
                } catch (Throwable t) {
                    Log.w(TAG, "parseResult error: " + t.getMessage());
                }
                if (results == null && resultCode == RESULT_OK && data != null) {
                    ClipData clipData = data.getClipData();
                    if (clipData != null && clipData.getItemCount() > 0) {
                        results = new Uri[clipData.getItemCount()];
                        for (int i = 0; i < clipData.getItemCount(); i++) {
                            results[i] = clipData.getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        results = new Uri[]{data.getData()};
                    } else if (data.getDataString() != null) {
                        results = new Uri[]{Uri.parse(data.getDataString())};
                    }
                }
                if (results != null) {
                    for (Uri uri : results) {
                        if (uri != null) {
                            try {
                                grantUriPermission(getPackageName(), uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            } catch (Throwable ignored) {}
                            try {
                                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                            } catch (Throwable ignored) {}
                        }
                    }
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == dev.continuum.antigravity.ui.download.FileDownloader.REQUEST_STORAGE_DOWNLOAD) {
            WebView wv = (webViewHost != null) ? webViewHost.getWebView() : null;
            dev.continuum.antigravity.ui.download.FileDownloader.onPermissionResult(this, wv, requestCode, grantResults);
        }
    }
}
