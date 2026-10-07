package com.agy.bridge;

import android.content.Context;
import android.webkit.JavascriptInterface;

import com.agy.oauth.OAuthManager;
import com.agy.runtime.RuntimeManager;
import com.agy.shell.ShizukuBridge;
import com.agy.theme.ThemeManager;
import com.agy.ui.MainActivity;

/**
 * The single JS bridge exposed to the WebView as the `Android` object
 * (aliased to `AndroidBridge` by the injected boot script). All methods are
 * fail-soft: they never throw into JS and return JSON / plain strings.
 */
public final class AndroidBridge {

    private final UiBridge ui = new UiBridge();
    private final ThemeBridge themeBridge;
    private final EngineBridge engine;
    private final EngineConfigJson config;
    private final ExternalUrlBridge external;
    private final ShellBridge shell;

    public AndroidBridge(Context context, RuntimeManager runtime,
                         ShizukuBridge shizuku, OAuthManager oauth,
                         ThemeManager theme) {
        Context appContext = context.getApplicationContext();
        this.themeBridge = new ThemeBridge(theme);
        this.engine = new EngineBridge(runtime);
        this.config = new EngineConfigJson(appContext, runtime, engine, shizuku, themeBridge);
        this.external = new ExternalUrlBridge(appContext, oauth);
        this.shell = new ShellBridge(shizuku);
    }

    /** Host activity, used to dismiss the native splash once the UI has painted. */
    public void setActivity(MainActivity activity) {
        ui.setActivity(activity);
    }

    /**
     * Called from web/patches/startup_ready.js after the UI's first paint.
     */
    @JavascriptInterface
    public void onUiReady() {
        ui.onUiReady();
    }

    @JavascriptInterface
    public void onUiError(final String errorMessage) {
        ui.onUiError(errorMessage);
    }

    @JavascriptInterface
    public boolean setTheme(String mode) {
        return themeBridge.setTheme(mode);
    }

    @JavascriptInterface
    public String getTheme() {
        return themeBridge.getTheme();
    }

    @JavascriptInterface
    public boolean isEngineReady() {
        return engine.isReady();
    }

    @JavascriptInterface
    public int getEnginePort() {
        return engine.getPort();
    }

    @JavascriptInterface
    public String getEngineBaseUrl() {
        return engine.getBaseUrl();
    }

    @JavascriptInterface
    public String getCsrfToken() {
        return engine.getCsrfToken();
    }

    @JavascriptInterface
    public String getConfig() {
        return config.get();
    }

    @JavascriptInterface
    public void openUrl(String url) {
        external.open(url);
    }

    @JavascriptInterface
    public String exec(String cmd) {
        return shell.exec(cmd);
    }

    @JavascriptInterface
    public String shizuku(String cmd) {
        return shell.exec(cmd);
    }

    @JavascriptInterface
    public boolean shizukuAvailable() {
        return shell.available();
    }
}
