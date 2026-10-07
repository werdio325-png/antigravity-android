package com.agy.util;

import java.io.File;
import java.io.IOException;

/** Canonical-path containment checks. */
public final class PathSafety {

    private PathSafety() {
    }

    public static boolean isInside(File root, File file) throws IOException {
        String rootPath = root.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        return filePath.equals(rootPath) || filePath.startsWith(rootPath + File.separator);
    }
}
