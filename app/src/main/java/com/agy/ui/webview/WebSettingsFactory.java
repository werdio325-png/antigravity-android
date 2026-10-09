package com.agy.ui.webview;

import android.os.Build;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.agy.runtime.RuntimeConfig;

/** Applies the full WebView settings profile (desktop UA, file access, TLS). */
public final class WebSettingsFactory {

    private static final String TAG = "WebViewHost";

    public static final String DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/130.0.0.0 Safari/537.36 Antigravity/" + RuntimeConfig.CORE_VERSION;

    private WebSettingsFactory() {
    }

    private static String defaultUserAgent = null;

    public static void configure(WebView view) {
        WebSettings settings = view.getSettings();
        if (defaultUserAgent == null) {
            defaultUserAgent = settings.getUserAgentString();
        }
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);

        boolean desktop = DesktopModePrefs.isDesktopMode(view.getContext());
        applyDesktopMode(view, desktop);

        // File + content access are needed for local assets requested by our UI.
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(true);
        settings.setAllowUniversalAccessFromFileURLs(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(false);
        }
        // Offscreen preraster: keeps tiles GPU-ready off the main thread (API 23+).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                settings.setOffscreenPreRaster(true);
            } catch (Exception e) {
                Log.w(TAG, "setOffscreenPreRaster failed", e);
            }
        }
    }

    public static void applyDesktopMode(WebView view, boolean desktop) {
        WebSettings settings = view.getSettings();
        if (defaultUserAgent == null) {
            defaultUserAgent = settings.getUserAgentString();
        }
        settings.setUserAgentString(desktop ? DESKTOP_UA : defaultUserAgent);
        settings.setUseWideViewPort(desktop);
        settings.setLoadWithOverviewMode(desktop);
    }
}
