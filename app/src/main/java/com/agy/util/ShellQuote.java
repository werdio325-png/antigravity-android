package com.agy.util;

/** POSIX single-quote a value unless it is already shell-safe. */
public final class ShellQuote {

    private ShellQuote() {
    }

    public static String quote(String value) {
        if (value.matches("[A-Za-z0-9_./:=+-]+")) {
            return value;
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
