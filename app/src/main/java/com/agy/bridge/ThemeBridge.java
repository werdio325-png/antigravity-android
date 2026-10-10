package com.agy.bridge;

import com.agy.theme.ThemeManager;

/** Theme get/set exposed to the Web UI. */
public final class ThemeBridge {

    private final ThemeManager theme;

    public ThemeBridge(ThemeManager theme) {
        this.theme = theme;
    }

    /**
     * Persist and apply the theme chosen in the Web UI, and switch the launcher
     * icon to match. Returns true when the mode was recognized.
     */
    public boolean setTheme(String mode) {
        return theme != null && theme.setTheme(mode);
    }

    public boolean setThemeByUser(String mode) {
        return theme != null && theme.setThemeByUser(mode);
    }

    public void onVisualThemeChanged(String visual) {
        if (theme != null) {
            theme.onVisualThemeChanged(visual);
        }
    }

    /** Effective persisted theme as "light"/"dark" (system mode resolved). */
    public String getTheme() {
        return theme != null ? theme.getTheme() : "dark";
    }
}
