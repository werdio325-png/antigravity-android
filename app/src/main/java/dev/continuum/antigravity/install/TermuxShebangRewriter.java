package dev.continuum.antigravity.install;

import android.util.Log;

import dev.continuum.antigravity.util.IoUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;

/** Rewrites termux shebangs to the app prefix, preserving interpreter args. */
public final class TermuxShebangRewriter {

    private static final String TAG = "TermuxShebangRewriter";

    private TermuxShebangRewriter() {
    }

    public static void rewrite(File prefix) {
        rewrite(prefix, prefix.getAbsolutePath());
    }

    /** Rewrites a staging tree while emitting shebangs for its eventual install location. */
    public static void rewrite(File root, String targetPrefix) {
        int rewritten = 0;
        for (String dir : EnvPaths.SCRIPT_DIRS) {
            rewritten += rewriteIn(new File(root, dir), targetPrefix);
        }
        if (rewritten > 0) {
            Log.i(TAG, "rewrote " + rewritten + " termux shebang(s) -> " + targetPrefix);
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
        if (file.length() < 2) {
            return false;
        }
        byte[] head = new byte[256];
        int headLen;
        try (InputStream in = new FileInputStream(file)) {
            headLen = IoUtil.readFully(in, head);
        } catch (IOException e) {
            return false;
        }
        if (headLen < 4 || head[0] != '#' || head[1] != '!') {
            return false;
        }
        int nl = -1;
        for (int i = 2; i < headLen; i++) {
            if (head[i] == '\n') {
                nl = i;
                break;
            }
        }
        // A line longer than the window is not safe to rewrite blindly.
        if (nl < 0 && headLen == head.length) {
            return false;
        }
        int lineEnd = nl >= 0 ? nl : headLen;
        String firstLine = new String(head, 0, lineEnd, StandardCharsets.ISO_8859_1);
        if (firstLine.endsWith("\r")) {
            firstLine = firstLine.substring(0, firstLine.length() - 1);
        }
        boolean isTermux = EnvPaths.TERMUX_SHEBANG.matcher(firstLine).matches();
        boolean isEnv = EnvPaths.ENV_SHEBANG.matcher(firstLine).matches();
        if (!isTermux && !isEnv) {
            return false;
        }
        byte[] full;
        try (InputStream in = new FileInputStream(file)) {
            full = IoUtil.readAllBytes(in);
        } catch (IOException e) {
            Log.w(TAG, "cannot read " + file + ": " + e);
            return false;
        }
        String content = new String(full, StandardCharsets.ISO_8859_1);
        String replaced = content;
        if (isTermux) {
            replaced = EnvPaths.TERMUX_SHEBANG_LINES.matcher(replaced)
                    .replaceAll("#!" + Matcher.quoteReplacement(newPrefix) + "$2");
        }
        if (isEnv || EnvPaths.ENV_SHEBANG_LINES.matcher(replaced).find()) {
            replaced = EnvPaths.ENV_SHEBANG_LINES.matcher(replaced)
                    .replaceAll("#!" + Matcher.quoteReplacement(newPrefix) + "/bin/$1$2");
        }
        if (replaced.equals(content)) {
            return false;
        }
        try (OutputStream out = new FileOutputStream(file)) {
            out.write(replaced.getBytes(StandardCharsets.ISO_8859_1));
        } catch (IOException e) {
            Log.w(TAG, "cannot rewrite shebang " + file + ": " + e);
            return false;
        }
        return true;
    }
}
