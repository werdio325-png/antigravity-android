package com.agy.theme;

import android.app.Activity;
import android.content.Context;

import com.agy.util.MainThreadPost;

/** Owns the persisted theme mode and resolved current context. */
public final class ThemeController {

    private final Context appContext;
    private Context currentContext;
    private int mode;

    public ThemeController(Context context) {
        this.appContext = context.getApplicationContext();
        this.currentContext = context;
        this.mode = ThemePrefs.load(this.appContext);
    }

    public void updateContext(Context context) {
        if (context != null) {
            this.currentContext = context;
        }
    }

    public int mode() {
        return mode;
    }

    public void setMode(int newMode) {
        if (newMode < ThemeColors.MODE_SYSTEM || newMode > ThemeColors.MODE_LIGHT) {
            return;
        }
        this.mode = newMode;
        ThemePrefs.save(appContext, newMode);
    }

    /**
     * Persist and apply an explicit "light"/"dark" choice (from the Web UI).
     * Returns false for any other value. Also keeps the launcher in sync.
     */
    public boolean setTheme(String theme) {
        if (ThemeColors.THEME_LIGHT.equalsIgnoreCase(theme)) {
            setMode(ThemeColors.MODE_LIGHT);
        } else if (ThemeColors.THEME_DARK.equalsIgnoreCase(theme)) {
            setMode(ThemeColors.MODE_DARK);
        } else if ("system".equalsIgnoreCase(theme) || "default".equalsIgnoreCase(theme)) {
            setMode(ThemeColors.MODE_SYSTEM);
        } else {
            return false;
        }
        // The bridge calls this on a WebView thread; window/bars must change on
        // the main thread. Apply right away so status/nav bars and the window
        // background track the Web UI without waiting for a resume.
        final Activity activity = currentContext instanceof Activity
                ? (Activity) currentContext : null;
        if (activity != null) {
            final boolean dark = isDark();
            MainThreadPost.post(new Runnable() {
                @Override
                public void run() {
                    ActivityPainter.apply(activity, dark);
                }
            });
        }
        return true;
    }

    /**
     * User explicitly selected a theme mode in Settings.
     * Updates theme and, if the launcher icon needs changing, in-place switches and exits.
     */
    public boolean setThemeByUser(String theme) {
        boolean changed = setTheme(theme);
        if (changed) {
            applyLauncherIcon();
        }
        return changed;
    }

    /** Called when the Web UI paints a visual theme, without altering user mode preference. */
    public void onVisualThemeChanged(final String visual) {
        final Activity activity = currentContext instanceof Activity
                ? (Activity) currentContext : null;
        if (activity != null) {
            final boolean dark = "dark".equalsIgnoreCase(visual);
            MainThreadPost.post(new Runnable() {
                @Override
                public void run() {
                    ActivityPainter.apply(activity, dark);
                }
            });
        }
    }

    /** Effective theme as a "light"/"dark" string (system mode is resolved). */
    public String getTheme() {
        return isDark() ? ThemeColors.THEME_DARK : ThemeColors.THEME_LIGHT;
    }

    public boolean isDark() {
        return isDark(currentContext != null ? currentContext : appContext);
    }

    public boolean isDark(Context context) {
        Context ctx = context != null ? context
                : (currentContext != null ? currentContext : appContext);
        return ThemeResolver.isDark(mode, ctx);
    }

    public int getBackgroundColor() {
        return isDark() ? ThemeColors.COLOR_DARK : ThemeColors.COLOR_LIGHT;
    }

    public String getBackgroundHex() {
        return isDark() ? ThemeColors.BG_DARK_HEX : ThemeColors.BG_LIGHT_HEX;
    }

    public String getForegroundHex() {
        return isDark() ? ThemeColors.FG_DARK_HEX : ThemeColors.FG_LIGHT_HEX;
    }

    public boolean isLauncherDark() {
        return mode == ThemeColors.MODE_DARK;
    }

    public void applyLauncherIcon() {
        LauncherIconSwitcher.apply(currentContext != null ? currentContext : appContext, isLauncherDark());
    }
}
