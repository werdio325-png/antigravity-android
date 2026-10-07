package com.agy.install;

import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.File;

/** Repoints absolute symlinks that point into the Termux prefix at our prefix. */
public final class TermuxSymlinkFixer {

    private static final String TAG = "TermuxSymlinkFixer";

    private TermuxSymlinkFixer() {
    }

    public static void fix(File prefix) {
        int fixed = fixIn(prefix, prefix.getAbsolutePath());
        if (fixed > 0) {
            Log.i(TAG, "repointed " + fixed + " termux symlink(s) -> " + prefix.getAbsolutePath());
        }
    }

    private static int fixIn(File dir, String newPrefix) {
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
                if (target == null || !target.startsWith(EnvPaths.TERMUX_PREFIX)) {
                    continue;
                }
                String suffix = target.substring(EnvPaths.TERMUX_PREFIX.length());
                if (!suffix.isEmpty() && !suffix.startsWith("/")) {
                    continue;
                }
                String newTarget = newPrefix + suffix;
                try {
                    if (!child.delete()) {
                        Log.w(TAG, "cannot delete symlink " + child);
                        continue;
                    }
                    Os.symlink(newTarget, child.getAbsolutePath());
                    fixed++;
                } catch (Exception e) {
                    Log.w(TAG, "cannot repoint symlink " + child + ": " + e);
                }
            } else if (child.isDirectory()) {
                fixed += fixIn(child, newPrefix);
            }
        }
        return fixed;
    }
}
