package com.agy.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

/** Writes files atomically (sibling temp file + rename) so readers see all or nothing. */
public final class AtomicFileWriter {

    private AtomicFileWriter() {
    }

    /** Text write via a sibling temp file then rename; falls back to a retry. */
    public static void writeText(File target, String content) {
        File dir = target.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            return;
        }
        File tmp = new File(dir, target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(content.getBytes(Charset.forName("UTF-8")));
            out.flush();
            out.getFD().sync();
        } catch (Exception e) {
            IoUtil.deleteQuietly(tmp);
            return;
        }
        if (tmp.renameTo(target)) {
            return;
        }
        if (target.delete() && tmp.renameTo(target)) {
            return;
        }
        IoUtil.deleteQuietly(tmp);
    }

    /**
     * Byte write used by the prefix rewriters: temp file, preserve permissions,
     * rename over. Falls back to an in-place write when rename-over is refused.
     * Returns true when the bytes landed.
     */
    public static boolean writeBytes(File file, byte[] data) {
        File dir = file.getParentFile();
        File tmp = null;
        try {
            tmp = File.createTempFile(file.getName() + ".", ".tmp", dir);
            try (OutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
            }
            FileModeUtil.copyPermissions(file, tmp);
            if (!tmp.renameTo(file)) {
                try (OutputStream out = new FileOutputStream(file)) {
                    out.write(data);
                }
                IoUtil.deleteQuietly(tmp);
            }
            return true;
        } catch (Exception e) {
            IoUtil.deleteQuietly(tmp);
            return false;
        }
    }
}
