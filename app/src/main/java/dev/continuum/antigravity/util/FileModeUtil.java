package dev.continuum.antigravity.util;

import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.File;

/** Applies unix modes/exec bits to extracted runtime files. */
public final class FileModeUtil {

    private static final String TAG = "FileModeUtil";

    private FileModeUtil() {
    }

    /** RuntimeManager semantics: readable + executable, no chmod syscall. */
    public static void recursiveSetReadableExecutable(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            Log.w(TAG, "makeBinExecutable: cannot list " + dir);
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                recursiveSetReadableExecutable(file);
                continue;
            }
            file.setReadable(true, false);
            if (!file.setExecutable(true, false)) {
                Log.w(TAG, "setExecutable failed: " + file);
            }
        }
    }

    /** EnvInstaller semantics: chmod 755 before the Java fallback bits. */
    public static void setExec(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        try {
            Os.chmod(file.getAbsolutePath(), 0755);
        } catch (ErrnoException e) {
            Log.w(TAG, "chmod failed " + file + ": " + e);
        }
        file.setReadable(true, false);
        if (!file.setExecutable(true, false)) {
            Log.w(TAG, "setExecutable failed: " + file);
        }
    }

    public static void recursiveSetExec(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            Log.w(TAG, "cannot list " + dir);
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                recursiveSetExec(file);
            } else {
                setExec(file);
            }
        }
    }

    public static void applyMode(File file, long mode) {
        try {
            Os.chmod(file.getAbsolutePath(), (int) (mode & 0777L));
        } catch (ErrnoException e) {
            Log.w(TAG, "chmod failed " + file + ": " + e);
        }
        boolean readable = (mode & 0400L) != 0;
        boolean writable = (mode & 0200L) != 0;
        boolean executable = (mode & 0100L) != 0 || file.isDirectory();
        file.setReadable(readable, false);
        file.setWritable(writable, false);
        file.setExecutable(executable, false);
    }

    public static void copyPermissions(File src, File dst) {
        try {
            int mode = Os.stat(src.getAbsolutePath()).st_mode & 0777;
            Os.chmod(dst.getAbsolutePath(), mode);
        } catch (ErrnoException e) {
            Log.w(TAG, "cannot stat/chmod mode between " + src + " and " + dst + ": " + e);
        }
    }
}
