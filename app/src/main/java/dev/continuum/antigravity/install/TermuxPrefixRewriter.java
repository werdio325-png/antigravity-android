package dev.continuum.antigravity.install;

import android.util.Log;

import dev.continuum.antigravity.util.AtomicFileWriter;
import dev.continuum.antigravity.util.IoUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;

/**
 * Full rewrite pass: replaces every Android app-private prefix
 * ({@code /data/data/com.<anyid>/files/usr}) with our prefix in text files.
 */
public final class TermuxPrefixRewriter {

    private static final String TAG = "TermuxPrefixRewriter";

    private TermuxPrefixRewriter() {
    }

    public static void rewrite(File prefix) {
        rewrite(prefix, prefix.getAbsolutePath());
    }

    /** Rewrites a staging tree while emitting paths for its eventual install location. */
    public static void rewrite(File root, String targetPrefix) {
        int rewritten = 0;
        for (String dir : EnvPaths.SCRIPT_DIRS) {
            rewritten += rewriteIn(new File(root, dir), targetPrefix);
        }
        if (rewritten > 0) {
            Log.i(TAG, "rewrote termux paths in " + rewritten + " file(s) -> " + targetPrefix);
        }
    }

    private static int rewriteIn(File dir, String newPrefix) {
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        int rewritten = 0;
        for (File child : children) {
            if (TextFileDetector.isSymlink(child)) {
                continue;
            }
            if (child.isDirectory()) {
                rewritten += rewriteIn(child, newPrefix);
            } else if (child.isFile() && rewriteFile(child, newPrefix)) {
                rewritten++;
            }
        }
        return rewritten;
    }

    private static boolean rewriteFile(File file, String newPrefix) {
        byte[] full;
        try (InputStream in = new FileInputStream(file)) {
            full = IoUtil.readAllBytes(in);
        } catch (IOException e) {
            return false;
        }
        if (!TextFileDetector.isTextFile(full)) {
            return false;
        }
        // Cheap marker test before the regex over the whole file.
        String content = new String(full, StandardCharsets.ISO_8859_1);
        if (!content.contains(EnvPaths.PREFIX_MARKER)) {
            return false;
        }
        String replaced = EnvPaths.TERMUX_ANY_PREFIX.matcher(content)
                .replaceAll(Matcher.quoteReplacement(newPrefix));
        if (replaced.equals(content)) {
            return false;
        }
        return AtomicFileWriter.writeBytes(file, replaced.getBytes(StandardCharsets.ISO_8859_1));
    }
}
