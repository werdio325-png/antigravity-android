package dev.continuum.antigravity.ui.webview;

import android.util.Log;

import dev.continuum.antigravity.bridge.AndroidBridge;
import dev.continuum.antigravity.theme.ThemeManager;

import org.json.JSONObject;

/** Injects the boot config and the startup_ready script ahead of the app UI. */
public final class AppHtmlInjector {

    private static final String TAG = "WebViewHost";

    /** Web layer generates the ready signal under generated/patches. */
    private static final String READY_SCRIPT = "./generated/patches/startup_ready.js";
    private static final String CSP = "default-src 'self' https: data: blob:; "
            + "script-src 'self' 'unsafe-inline' 'unsafe-eval' blob:; "
            + "style-src 'self' 'unsafe-inline' https:; "
            + "img-src 'self' https: data: blob:; font-src 'self' https: data:; "
            + "connect-src 'self' https: wss:; frame-src https: data: blob:; "
            + "worker-src 'self' blob:; object-src 'none'; base-uri 'self'";

    private AppHtmlInjector() {
    }

    /**
     * Appends the app version to every local script/stylesheet URL.
     *
     * Asset filenames never change between releases, so a WebView that ignored
     * our revalidation headers could keep an older bundle. A versioned query
     * makes the URL itself new after an update, so the first launch of a new
     * release always fetches the matching assets.
     */
    static String cacheBustLocalAssets(String html, JSONObject config) {
        if (html == null || config == null) {
            return html;
        }
        String version = config.optString("appVersion", "");
        int code = config.optInt("appVersionCode", 0);
        if (version.isEmpty() && code == 0) {
            return html;
        }
        String stamp = version + "-" + code;
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(src|href)=\"\\./([^\"?#]+)\"")
                .matcher(html);
        StringBuffer out = new StringBuffer(html.length() + 256);
        while (matcher.find()) {
            matcher.appendReplacement(out, matcher.group(1) + "=\"./"
                    + matcher.group(2).replace("$", "\\$").replace("\\", "\\\\")
                    + "?v=" + stamp + "\"");
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public static String inject(String html, AndroidBridge bridge, ThemeManager theme) {
        String boot;
        JSONObject config = null;
        try {
            config = new JSONObject(bridge.getConfig());
            JSONObject endpoints = new JSONObject();
            String base = bridge.getEngineBaseUrl();
            endpoints.put("core", base);
            endpoints.put("core_http", base);
            endpoints.put("enginePort", bridge.getEnginePort());
            boot = "<meta http-equiv=\"Content-Security-Policy\" content=\"" + CSP + "\">"
                    + "<script>(function(){"
                    + "window.__APP_CONFIG__=" + config.toString() + ";"
                    + "window.__AG_ENDPOINTS__=" + endpoints.toString() + ";"
                    + "window.__AG_CSRF__=" + JSONObject.quote(bridge.getCsrfToken()) + ";"
                    + "window.__AG_HOST_THEME__="
                    + JSONObject.quote(theme != null && theme.mode() == dev.continuum.antigravity.theme.ThemeColors.MODE_DARK ? "dark"
                            : (theme != null && theme.mode() == dev.continuum.antigravity.theme.ThemeColors.MODE_LIGHT ? "light" : "system")) + ";"
                    + "window.__AG_RESOLVED_THEME__="
                    + JSONObject.quote(theme != null ? theme.getTheme() : "light") + ";"
                    + "window.__AG_NATIVE_BRIDGE_TOKEN__="
                    + JSONObject.quote(bridge.getBlobBridgeToken()) + ";"
                    + "window.AndroidBridge=window.Android;"
                    + "window.dialog={showOpenDialog:function(){return new Promise(function(resolve){window.__onDirectoryPicked=function(path){resolve(path);};if(window.Android&&typeof window.Android.openDirectoryPicker==='function'){window.Android.openDirectoryPicker();}else{resolve(null);}});}};"
                    + "})();</script>"
                    // Signals first paint back to native (AndroidBridge.onUiReady)
                    // so the splash is dismissed on real content, not a timer.
                    + "<script src=\"" + READY_SCRIPT + "\"></script>";
        } catch (Exception e) {
            Log.w(TAG, "config injection failed", e);
            return html;
        }
        if (config != null) {
            html = cacheBustLocalAssets(html, config);
        }
        int head = html.indexOf("<head>");
        if (head >= 0) {
            int at = head + "<head>".length();
            return html.substring(0, at) + "\n" + boot + html.substring(at);
        }
        return boot + html;
    }
}
