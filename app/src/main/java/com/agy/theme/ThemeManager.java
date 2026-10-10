package com.agy.theme;

import android.app.Activity;
import android.content.Context;
import android.webkit.WebView;

/**
 * Three user modes (system/dark/light) collapse to two real themes:
 * dark #101010 and light #ffffff. Thin facade over the theme helpers.
 */
public final class ThemeManager {

    private final ThemeController controller;

    public ThemeManager(Context context) {
        this.controller = new ThemeController(context);
    }

    public void updateContext(Context context) {
        controller.updateContext(context);
    }

    public int mode() {
        return controller.mode();
    }

    /**
     * Persist and apply an explicit "system"/"light"/"dark" choice (from the Web UI).
     * Returns false for any other value. Also keeps the launcher in sync.
     */
    public boolean setTheme(String theme) {
        return controller.setTheme(theme);
    }

    public boolean setThemeByUser(String theme) {
        return controller.setThemeByUser(theme);
    }

    public void onVisualThemeChanged(String visual) {
        controller.onVisualThemeChanged(visual);
    }

    /** Effective theme as a "light"/"dark" string (system mode is resolved). */
    public String getTheme() {
        return controller.getTheme();
    }

    public boolean isDark() {
        return controller.isDark();
    }

    public int getBackgroundColor() {
        return controller.getBackgroundColor();
    }

    public String getBackgroundHex() {
        return controller.getBackgroundHex();
    }

    public String getForegroundHex() {
        return controller.getForegroundHex();
    }

    public void applyLauncherIcon() {
        controller.applyLauncherIcon();
    }

    public void applyToActivity(Activity activity) {
        if (activity != null) {
            ActivityPainter.apply(activity, controller.isDark());
        }
    }

    public void applyToWebView(WebView webView) {
        WebViewPainter.apply(webView, controller.getBackgroundColor());
    }
}
