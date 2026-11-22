package com.agy.ui.extra;

import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.agy.util.SensitiveDataRedactor;

/**
 * Isolated native Android dialog hosting a bridge-free WebView for loopback
 * localhost/127.0.0.1 live previews. Never exposes AndroidBridge or file:// access.
 */
public final class LocalPreviewDialog extends Dialog {

    private static final String TAG = "LocalPreviewDialog";

    private final WebView previewWebView;
    private final TextView urlView;
    private final ProgressBar progressBar;
    private boolean destroyed = false;

    public LocalPreviewDialog(Activity activity, String initialUrl, boolean darkTheme) {
        super(activity, android.R.style.Theme_DeviceDefault_NoActionBar);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        int bgColor = darkTheme ? 0xFF101010 : 0xFFFFFFFF;
        int barBg = darkTheme ? 0xFF1A1A1A : 0xFFF4F4F5;
        int dividerColor = darkTheme ? 0xFF2D2D2D : 0xFFE4E4E7;
        int textColor = darkTheme ? 0xFFF4F4F5 : 0xFF18181B;
        int subTextColor = darkTheme ? 0xFFA1A1AA : 0xFF52525B;
        int btnBg = darkTheme ? 0xFF27272A : 0xFFE4E4E7;

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bgColor);

        // Toolbar
        LinearLayout toolbar = new LinearLayout(activity);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(barBg);
        toolbar.setPadding(dp(activity, 8), dp(activity, 6), dp(activity, 8), dp(activity, 6));
        root.addView(toolbar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView backBtn = makeToolbarButton(activity, "\u2190", btnBg, textColor);
        backBtn.setContentDescription("Back");
        toolbar.addView(backBtn, toolbarBtnParams(activity, 0, 6));

        TextView reloadBtn = makeToolbarButton(activity, "\u21BB", btnBg, textColor);
        reloadBtn.setContentDescription("Reload");
        toolbar.addView(reloadBtn, toolbarBtnParams(activity, 0, 8));

        urlView = new TextView(activity);
        urlView.setText(initialUrl);
        urlView.setSingleLine(true);
        urlView.setEllipsize(TextUtils.TruncateAt.END);
        urlView.setTextColor(subTextColor);
        urlView.setTypeface(Typeface.MONOSPACE);
        urlView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        urlView.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams urlParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        toolbar.addView(urlView, urlParams);

        TextView closeBtn = makeToolbarButton(activity, "\u2715", btnBg, textColor);
        closeBtn.setContentDescription("Close preview");
        toolbar.addView(closeBtn, toolbarBtnParams(activity, 8, 0));

        // Progress bar
        progressBar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setIndeterminate(false);
        progressBar.setVisibility(View.GONE);
        root.addView(progressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 3)));

        View divider = new View(activity);
        divider.setBackgroundColor(dividerColor);
        root.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(activity, 1)));

        // Isolated WebView (strictly NO addJavascriptInterface)
        previewWebView = new WebView(activity);
        previewWebView.setBackgroundColor(bgColor);
        configureIsolatedSettings(previewWebView);
        previewWebView.setWebViewClient(new LoopbackOnlyWebViewClient());
        previewWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                if (newProgress >= 100) {
                    progressBar.setVisibility(View.GONE);
                } else {
                    progressBar.setVisibility(View.VISIBLE);
                    progressBar.setProgress(newProgress);
                }
            }
        });
        root.addView(previewWebView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f));

        backBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (previewWebView.canGoBack()) {
                    previewWebView.goBack();
                } else {
                    dismiss();
                }
            }
        });
        reloadBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                previewWebView.reload();
            }
        });
        closeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });

        setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        Window window = getWindow();
        if (window != null) {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(barBg);
            window.setNavigationBarColor(bgColor);
        }

        setOnKeyListener(new OnKeyListener() {
            @Override
            public boolean onKey(DialogInterface dialog, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    if (previewWebView.canGoBack()) {
                        previewWebView.goBack();
                        return true;
                    }
                }
                return false;
            }
        });

        setOnDismissListener(new OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                destroyWebView();
            }
        });

        if (isAllowedLoopbackUrl(initialUrl)) {
            previewWebView.loadUrl(initialUrl.trim());
        }
    }

    private static void configureIsolatedSettings(WebView webView) {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }
    }

    public static boolean isAllowedLoopbackUrl(String rawUrl) {
        if (rawUrl == null) {
            return false;
        }
        String trimmed = rawUrl.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        try {
            Uri uri = Uri.parse(trimmed);
            return isAllowedLoopbackUri(uri);
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean isAllowedLoopbackUri(Uri uri) {
        if (uri == null || uri.getUserInfo() != null) {
            return false;
        }
        String scheme = uri.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            return false;
        }
        String host = uri.getHost();
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
    }

    private void destroyWebView() {
        if (destroyed) {
            return;
        }
        destroyed = true;
        try {
            previewWebView.stopLoading();
            previewWebView.setWebChromeClient(null);
            previewWebView.setWebViewClient(null);
            ViewGroup parent = (ViewGroup) previewWebView.getParent();
            if (parent != null) {
                parent.removeView(previewWebView);
            }
            previewWebView.destroy();
        } catch (Exception e) {
            Log.w(TAG, "error destroying preview WebView", e);
        }
    }

    private final class LoopbackOnlyWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (request == null || request.getUrl() == null) {
                return true;
            }
            Uri uri = request.getUrl();
            if ("about".equalsIgnoreCase(uri.getScheme())) {
                return false;
            }
            if (!isAllowedLoopbackUri(uri)) {
                Log.w(TAG, "blocked non-loopback navigation in LocalPreviewDialog: "
                        + SensitiveDataRedactor.safeUrl(uri.toString()));
                return true;
            }
            return false;
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (url == null || "about:blank".equals(url)) {
                return false;
            }
            if (!isAllowedLoopbackUrl(url)) {
                Log.w(TAG, "blocked non-loopback navigation in LocalPreviewDialog: "
                        + SensitiveDataRedactor.safeUrl(url));
                return true;
            }
            return false;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (url != null && !"about:blank".equals(url) && !isAllowedLoopbackUrl(url)) {
                view.stopLoading();
                return;
            }
            if (url != null) {
                urlView.setText(url);
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
        }
    }

    private static TextView makeToolbarButton(Activity activity, String label, int bgColor, int textColor) {
        TextView tv = new TextView(activity);
        tv.setText(label);
        tv.setTextColor(textColor);
        tv.setGravity(Gravity.CENTER);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(activity, 6));
        bg.setColor(bgColor);
        tv.setBackground(bg);
        return tv;
    }

    private static LinearLayout.LayoutParams toolbarBtnParams(Activity activity, int leftDp, int rightDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(activity, 34), dp(activity, 34));
        lp.leftMargin = dp(activity, leftDp);
        lp.rightMargin = dp(activity, rightDp);
        return lp;
    }

    private static int dp(Activity activity, int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, activity.getResources().getDisplayMetrics()));
    }
}
