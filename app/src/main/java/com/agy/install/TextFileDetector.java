package com.agy.install;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.File;

/** Detects text files (no NUL, not ELF) and symlinks. */
public final class TextFileDetector {

    private TextFileDetector() {
    }

    public static boolean isTextFile(byte[] data) {
        if (data.length >= 4
                && data[0] == 0x7f && data[1] == 'E' && data[2] == 'L' && data[3] == 'F') {
            return false;
        }
        for (byte b : data) {
            if (b == 0) {
                return false;
            }
        }
        return true;
    }

    public static boolean isSymlink(File file) {
        try {
            return OsConstants.S_ISLNK(Os.lstat(file.getAbsolutePath()).st_mode);
        } catch (ErrnoException e) {
            return false;
        }
    }
}
