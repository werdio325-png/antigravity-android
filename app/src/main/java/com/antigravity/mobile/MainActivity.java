package com.antigravity.mobile;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

public class MainActivity extends Activity {
    private WebView webView;
    private WebView splashView;
    private FrameLayout rootLayout;
    private boolean interfaceLoaded = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private static final int FILE_CHOOSER_REQUEST_CODE = 1002;
    private ValueCallback<Uri[]> fileChooserCallback = null;

    private boolean isDarkTheme() {
        return (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private final Runnable pollServer = new Runnable() {
        @Override
        public void run() {
            if (CoreServerService.serverUrl != null && webView != null) {
                // Wait ~1 second after core initialization before loading interface
                handler.postDelayed(() -> {
                    if (webView != null && CoreServerService.serverUrl != null) {
                        String theme = isDarkTheme() ? "dark" : "light";
                        String url = CoreServerService.serverUrl + "&hostTheme=" + theme;
                        webView.loadUrl(url);
                    }
                }, 1000);
            } else {
                handler.postDelayed(this, 50);
            }
        }
    };

    public static void openCustomTab(Context context, String url) {
        if (url == null || url.trim().isEmpty()) return;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Bundle extras = new Bundle();
            extras.putBinder("android.support.customtabs.extra.SESSION", (IBinder) null);
            intent.putExtras(extras);
            intent.putExtra("android.support.customtabs.extra.TOOLBAR_COLOR", 0xFF121214);
            intent.putExtra("android.support.customtabs.extra.TITLE_VISIBILITY", 1);
            intent.setPackage("com.android.chrome");
            context.startActivity(intent);
        } catch (Exception e) {
            try {
                Intent fallback = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(fallback);
            } catch (Exception ignored) {}
        }
    }

    private boolean handleUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        if (url.startsWith("https://127.0.0.1") || url.startsWith("http://127.0.0.1") ||
            url.startsWith("https://localhost") || url.startsWith("http://localhost")) {
            return false;
        }
        openCustomTab(this, url);
        return true;
    }

    private void dismissSplashWithFade() {
        if (splashView == null || interfaceLoaded) return;
        interfaceLoaded = true;
        // Smooth cross-fade transition
        splashView.animate()
            .alpha(0f)
            .setDuration(400)
            .withEndAction(() -> {
                if (rootLayout != null && splashView != null) {
                    rootLayout.removeView(splashView);
                    splashView.destroy();
                    splashView = null;
                }
            })
            .start();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);

        boolean isDark = (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        String bg = isDark ? "#121214" : "#ffffff";
        getWindow().setStatusBarColor(Color.parseColor(bg));
        getWindow().setNavigationBarColor(Color.parseColor(bg));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            if (!isDark) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }

        rootLayout = new FrameLayout(this);
        rootLayout.setBackgroundColor(Color.parseColor(bg));
        setContentView(rootLayout);

        // 1. Main WebView for core app
        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor(bg));
        rootLayout.addView(webView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Antigravity/2.13.0");

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> filePathCallback, FileChooserParams fcp) {
                if (fileChooserCallback != null) {
                    fileChooserCallback.onReceiveValue(null);
                    fileChooserCallback = null;
                }
                fileChooserCallback = filePathCallback;

                try {
                    Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.setType("*/*");

                    // Поддержка фильтров MIME-типов, если переданы сайтом
                    if (fcp != null && fcp.getAcceptTypes() != null && fcp.getAcceptTypes().length > 0) {
                        String[] types = fcp.getAcceptTypes();
                        if (types.length == 1 && types[0] != null && !types[0].trim().isEmpty()) {
                            intent.setType(types[0]);
                        } else if (types.length > 1) {
                            intent.putExtra(Intent.EXTRA_MIME_TYPES, types);
                        }
                    }

                    // Поддержка множественного выбора файлов/изображений
                    if (fcp != null && fcp.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    } else {
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    }

                    startActivityForResult(Intent.createChooser(intent, "Выберите файлы или изображения"), FILE_CHOOSER_REQUEST_CODE);
                    return true;
                } catch (Exception e) {
                    if (fileChooserCallback != null) {
                        fileChooserCallback.onReceiveValue(null);
                        fileChooserCallback = null;
                    }
                    return false;
                }
            }

            @Override
            public boolean onCreateWindow(WebView v, boolean isDialog, boolean isUserGesture, Message msg) {
                WebView temp = new WebView(MainActivity.this);
                temp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                        return handleUrl(req != null && req.getUrl() != null ? req.getUrl().toString() : null);
                    }
                });
                ((WebView.WebViewTransport) msg.obj).setWebView(temp);
                msg.sendToTarget();
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) {
                h.proceed(); // Trust local loopback TLS
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return handleUrl(req != null && req.getUrl() != null ? req.getUrl().toString() : null);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (url != null && url.contains("/auth/callback") && CoreServerService.serverUrl != null) {
                    handler.postDelayed(() -> v.loadUrl(CoreServerService.serverUrl), 1000);
                } else if (url != null && CoreServerService.serverUrl != null && url.startsWith(CoreServerService.serverUrl)) {
                    // Interface has finished rendering first page, smoothly dismiss splash
                    handler.postDelayed(MainActivity.this::dismissSplashWithFade, 100);
                }
            }
        });

        // 2. Dedicated Splash Overlay with exact 3 dots from core
        splashView = new WebView(this);
        splashView.setBackgroundColor(Color.parseColor(bg));
        rootLayout.addView(splashView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        String fg = isDark ? "#ffffff" : "#121214";
        String splashHtml = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1.0'><style>"
            + "html,body{margin:0;padding:0;height:100%;width:100%;display:flex;align-items:center;justify-content:center;overflow:hidden;"
            + "background:" + bg + ";color:" + fg + ";}"
            + "@keyframes dot-bounce{0%,100%{transform:translateY(0)}15%{transform:translateY(-2px)}30%{transform:translateY(0)}}"
            + ".animate-dot-bounce{animation:dot-bounce 1.6s ease-in-out infinite;display:inline-block;width:3px;height:3px;border-radius:50%;background-color:currentColor;}"
            + "</style></head><body>"
            + "<span style='display:inline-flex;align-items:center;gap:2px;height:16px;padding:0 4px;opacity:0.5;' aria-label='Loading'>"
            + "<span class='animate-dot-bounce' style='animation-delay:0ms;'></span>"
            + "<span class='animate-dot-bounce' style='animation-delay:120ms;'></span>"
            + "<span class='animate-dot-bounce' style='animation-delay:240ms;'></span>"
            + "</span></body></html>";
        splashView.loadDataWithBaseURL(null, splashHtml, "text/html", "utf-8", null);

        // Request storage permissions if needed
        requestStoragePermissions();

        // Start Core Service
        Intent intent = new Intent(this, CoreServerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }

        handler.postDelayed(pollServer, 500);
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
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (fileChooserCallback == null) return;
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            fileChooserCallback.onReceiveValue(results);
            fileChooserCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }
}
