package dev.continuum.antigravity.install;

import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.util.UUID;
import java.util.regex.Matcher;

/** Repoints absolute Android-private symlinks at the active app prefix. */
public final class TermuxSymlinkFixer {

    private static final String TAG = "TermuxSymlinkFixer";

    private TermuxSymlinkFixer() {
    }

    public static int fix(File prefix) {
        return fix(prefix, prefix.getAbsolutePath());
    }

    /** Rewrites links in a staging tree to the final (stable) prefix path. */
    public static int fix(File root, String targetPrefix) {
        int fixed = fixIn(root, targetPrefix);
        if (fixed > 0) {
            Log.i(TAG, "repointed " + fixed + " Android-prefix symlink(s) -> " + targetPrefix);
        }
        return fixed;
    }

    private static int fixIn(File dir, String targetPrefix) {
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        int fixed = 0;
        for (File child : children) {
            if (TextFileDetector.isSymlink(child)) {
                String target;
                try {
                    target = Os.readlink(child.getAbsolutePath());
                } catch (ErrnoException e) {
                    Log.w(TAG, "readlink failed " + child + ": " + e);
                    continue;
                }
                if (target == null) {
                    continue;
                }
                Matcher matcher = EnvPaths.TERMUX_ANY_PREFIX.matcher(target);
                if (!matcher.lookingAt()) {
                    continue;
                }
                String suffix = target.substring(matcher.end());
                if (!suffix.isEmpty() && !suffix.startsWith("/")) {
                    continue;
                }
                String newTarget = targetPrefix + suffix;
                File temporary = new File(child.getParentFile(),
                        child.getName() + ".agy-link-" + UUID.randomUUID());
                try {
                    Os.symlink(newTarget, temporary.getAbsolutePath());
                    Os.rename(temporary.getAbsolutePath(), child.getAbsolutePath());
                    fixed++;
                } catch (Exception e) {
                    try {
                        Os.remove(temporary.getAbsolutePath());
                    } catch (ErrnoException ignored) {
                    }
                    Log.w(TAG, "cannot repoint symlink " + child + ": " + e);
                }
            } else if (child.isDirectory()) {
                fixed += fixIn(child, targetPrefix);
            }
        }
        return fixed;
    }
}
