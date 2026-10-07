package com.agy.install;

import android.content.Context;

import java.io.File;
import java.util.regex.Pattern;

/** All filesystem/asset constants and the app-private prefix location. */
public final class EnvPaths {

    private EnvPaths() {
    }

    public static final String PREFIX_DIR = "usr";
    public static final String MARKER = ".installed";
    public static final String BASH_REL = "bin/bash";
    /** User data (token/settings/chats); the regenerable prefix must never collide. */
    public static final String USER_DATA_DIR = ".gemini";

    public static final String ASSET_BOOTSTRAP = "env/bootstrap/bootstrap.tar.gz";
    public static final String ASSET_BOOTSTRAP_PLAIN = "env/bootstrap/bootstrap.tar";
    public static final String ASSET_OVERLAY = "env/overlay";
    public static final String ASSET_PKG = "env/pkg";
    public static final String ASSET_BUS = "bus";

    /**
     * The Termux bootstrap is compiled with {@code /data/data/com.termux/files/usr}
     * hardcoded: in executable shell scripts (shebangs) and in a handful of
     * absolute symlinks. Our app-private prefix is different, so both must be
     * repointed or the kernel answers {@code bad interpreter: Permission denied}.
     */
    public static final String TERMUX_PREFIX = "/data/data/com.termux/files/usr";
    public static final Pattern TERMUX_SHEBANG =
            Pattern.compile("^#!(/data/data/com\\.[^/]*/files/usr)(.*)$");
    /** Same pattern, applied per-line across a whole script (see gzexe). */
    public static final Pattern TERMUX_SHEBANG_LINES =
            Pattern.compile("(?m)^#!(/data/data/com\\.[^/]*/files/usr)([^\\n]*)$");
    /**
     * Any Android app-private prefix, not only Termux: {@code com.termux} (the
     * bootstrap), {@code com.tlx}, and our own baked {@code com.agy} default all
     * appear in text scripts. Used for the full (non-shebang) rewrite pass.
     */
    public static final Pattern TERMUX_ANY_PREFIX =
            Pattern.compile("/data/data/com\\.[^/]*/files/usr");
    /** Cheap prefilter before running the regex over a whole file. */
    public static final String PREFIX_MARKER = "/data/data/com.";
    /** Directory that holds the reusable post-install path fixer. */
    public static final String FIX_PATHS_REL = "bin/agy-fix-paths";
    /** Directories that hold shell scripts; bin + libexec are the ones exec'd. */
    public static final String[] SCRIPT_DIRS = {"bin", "libexec", "etc", "share", "var"};

    public static final String TOYBOX = "/system/bin/toybox";
    public static final String TAR = "/system/bin/tar";
    public static final String CHMOD = "/system/bin/chmod";

    public static File prefix(Context context) {
        return new File(context.getFilesDir(), PREFIX_DIR);
    }
}
