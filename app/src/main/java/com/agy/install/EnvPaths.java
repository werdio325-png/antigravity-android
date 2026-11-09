package com.agy.install;

import android.content.Context;

import java.io.File;
import java.util.regex.Pattern;

/** Resolved filesystem layout and asset names for the Bionic environment. */
public final class EnvPaths {

    private EnvPaths() {
    }

    public static final String PREFIX_DIR = "usr";
    public static final String MARKER = ".installed";
    public static final String BASH_REL = "bin/bash";
    /** User data (tokens/settings/chats); the mutable env prefix must not overlap it. */
    public static final String USER_DATA_DIR = ".gemini";

    public static final String ASSET_BOOTSTRAP = "env/bootstrap/bootstrap.tar.gz";
    public static final String ASSET_BOOTSTRAP_PLAIN = "env/bootstrap/bootstrap.tar";
    public static final String ASSET_MANIFEST = "env/manifest.json";
    public static final String ASSET_OVERLAY = "env/overlay";
    public static final String ASSET_PKG = "env/pkg";
    public static final String ASSET_BUS = "bus";

    /** Termux and AGY prefixes occur in both legacy /data/data and modern /data/user/N paths. */
    private static final String ANDROID_PREFIX_REGEX =
            "/data/(?:data|user/[0-9]+)/[^/]+/files/usr";

    public static final String TERMUX_PREFIX = "/data/data/com.termux/files/usr";
    public static final Pattern TERMUX_SHEBANG =
            Pattern.compile("^#![ \\t]*(" + ANDROID_PREFIX_REGEX + ")(.*)$");
    /** Same pattern, applied per-line across a whole script (see gzexe). */
    public static final Pattern TERMUX_SHEBANG_LINES =
            Pattern.compile("(?m)^#![ \\t]*(" + ANDROID_PREFIX_REGEX + ")([^\\n]*)$");
    /** Linux standard /usr/bin/env shebang pattern (not supported on Android bionic). */
    public static final Pattern ENV_SHEBANG =
            Pattern.compile("^#![ \\t]*/usr/bin/env[ \\t]+(?:-S[ \\t]+)?([^ \\t\\r\\n]+)(.*)$");
    public static final Pattern ENV_SHEBANG_LINES =
            Pattern.compile("(?m)^#![ \\t]*/usr/bin/env[ \\t]+(?:-S[ \\t]+)?([^ \\t\\r\\n]+)([^\\n]*)$");
    /** Any app-private prefix ending at the usr path component. */
    public static final Pattern TERMUX_ANY_PREFIX =
            Pattern.compile(ANDROID_PREFIX_REGEX + "(?=/|$)");
    /** Cheap prefilter before applying the more specific path expression. */
    public static final String PREFIX_MARKER = "/data/";
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
