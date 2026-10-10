package com.agy.ui.webview;

import android.util.Log;

import com.agy.bridge.AndroidBridge;
import com.agy.theme.ThemeManager;

import org.json.JSONObject;

/** Injects the boot config and the startup_ready script ahead of the app UI. */
public final class AppHtmlInjector {

    private static final String TAG = "WebViewHost";

    /** Web layer generates the ready signal under generated/patches. */
    private static final String READY_SCRIPT = "./generated/patches/startup_ready.js";

    private AppHtmlInjector() {
    }

    public static String inject(String html, AndroidBridge bridge, ThemeManager theme) {
        String boot;
        try {
            JSONObject config = new JSONObject(bridge.getConfig());
            JSONObject endpoints = new JSONObject();
            String base = bridge.getEngineBaseUrl();
            endpoints.put("core", base);
            endpoints.put("core_http", base);
            endpoints.put("enginePort", bridge.getEnginePort());
            boot = "<script>(function(){"
                    + "window.__APP_CONFIG__=" + config.toString() + ";"
                    + "window.__AG_ENDPOINTS__=" + endpoints.toString() + ";"
                    + "window.__AG_CSRF__=" + JSONObject.quote(bridge.getCsrfToken()) + ";"
                    + "window.__AG_HOST_THEME__="
                    + JSONObject.quote(theme != null && theme.mode() == com.agy.theme.ThemeColors.MODE_DARK ? "dark"
                            : (theme != null && theme.mode() == com.agy.theme.ThemeColors.MODE_LIGHT ? "light" : "system")) + ";"
                    + "window.__AG_RESOLVED_THEME__="
                    + JSONObject.quote(theme != null ? theme.getTheme() : "light") + ";"
                    + "window.AndroidBridge=window.Android;"
                    + "})();</script>"
                    // Signals first paint back to native (AndroidBridge.onUiReady)
                    // so the splash is dismissed on real content, not a timer.
                    + "<script src=\"" + READY_SCRIPT + "\"></script>";
        } catch (Exception e) {
            Log.w(TAG, "config injection failed", e);
            return html;
        }
        int head = html.indexOf("<head>");
        if (head >= 0) {
            int at = head + "<head>".length();
            return html.substring(0, at) + "\n" + boot + html.substring(at);
        }
        return boot + html;
    }
}
