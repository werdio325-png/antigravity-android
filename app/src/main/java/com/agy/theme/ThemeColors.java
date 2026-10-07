package com.agy.theme;

/** Resolved colors and mode names. Three modes collapse to two real themes. */
public final class ThemeColors {

    public static final int MODE_SYSTEM = 0;
    public static final int MODE_DARK = 1;
    public static final int MODE_LIGHT = 2;

    public static final int COLOR_DARK = 0xFF101010;
    public static final int COLOR_LIGHT = 0xFFFFFFFF;

    public static final String THEME_DARK = "dark";
    public static final String THEME_LIGHT = "light";

    public static final String BG_DARK_HEX = "#101010";
    public static final String BG_LIGHT_HEX = "#ffffff";
    public static final String FG_DARK_HEX = "#ffffff";
    public static final String FG_LIGHT_HEX = "#101010";

    private ThemeColors() {
    }
}
