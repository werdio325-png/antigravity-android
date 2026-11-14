package dev.continuum.antigravity.install;

import android.content.res.AssetManager;
import android.util.Log;

import dev.continuum.antigravity.util.AssetUnpacker;
import dev.continuum.antigravity.util.IoUtil;
import dev.continuum.antigravity.util.Sha256;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Stages and extracts the pinned bootstrap; fallback extraction must preserve its symlinks. */
public final class BootstrapExtractor {

    private static final String TAG = "BootstrapExtractor";

    private BootstrapExtractor() {
    }

    public static void extract(AssetManager assets, String asset, File prefix) {
        extract(assets, asset, prefix, null, 0);
    }

    public static void extract(AssetManager assets, String asset, File prefix,
                               String expectedSha256, int expectedSymlinkCount) {
        File tmp = null;
        try {
            tmp = File.createTempFile("bootstrap", ".tar", prefix);
            AssetUnpacker.copyFile(assets, asset, tmp);
            if (expectedSha256 != null) {
                String actual = Sha256.of(tmp);
                if (!expectedSha256.equals(actual)) {
                    throw new IOException("bootstrap sha256 mismatch: got " + actual
                            + " want " + expectedSha256);
                }
            }
        } catch (Exception e) {
            IoUtil.deleteQuietly(tmp);
            throw new IllegalStateException("cannot stage/verify " + asset + ": " + e.getMessage(), e);
        }

        boolean gzip = isGzip(tmp);
        Log.i(TAG, "bootstrap " + asset + " format=" + (gzip ? "gzip" : "plain-tar"));

        try {
            if (TarRunner.runToyboxTar(tmp, prefix, gzip)
                    && new File(prefix, "bin").isDirectory()
                    && countSymlinks(prefix) >= expectedSymlinkCount) {
                verifyRequiredFiles(prefix, expectedSymlinkCount);
                Log.i(TAG, "bootstrap extracted with system tar");
                return;
            }
            Log.w(TAG, "system tar unavailable/incomplete; using path-safe Java fallback");
            int extractedLinks;
            try (InputStream in = new FileInputStream(tmp)) {
                extractedLinks = gzip
                        ? TarExtractor.untarGz(in, prefix)
                        : TarExtractor.untar(in, prefix);
            }
            if (extractedLinks < expectedSymlinkCount) {
                throw new IOException("bootstrap symlink count mismatch: extracted "
                        + extractedLinks + " expected at least " + expectedSymlinkCount);
            }
            verifyRequiredFiles(prefix, expectedSymlinkCount);
            Log.i(TAG, "bootstrap extracted with Java fallback; symlinks=" + extractedLinks);
        } catch (IOException e) {
            throw new IllegalStateException("bootstrap extraction failed: " + e.getMessage(), e);
        } finally {
            IoUtil.deleteQuietly(tmp);
        }
    }

    private static void verifyRequiredFiles(File prefix, int expectedSymlinkCount) throws IOException {
        File bin = new File(prefix, "bin");
        File bash = new File(bin, "bash");
        File sh = new File(bin, "sh");
        if (!bin.isDirectory() || !bash.isFile() || !sh.isFile()) {
            throw new IOException("bootstrap is missing required shell files under " + bin);
        }
        if (expectedSymlinkCount > 0 && !TextFileDetector.isSymlink(sh)) {
            throw new IOException("bootstrap bin/sh symlink was not preserved");
        }
    }

    private static int countSymlinks(File dir) {
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        int count = 0;
        for (File child : children) {
            if (TextFileDetector.isSymlink(child)) {
                count++;
            } else if (child.isDirectory()) {
                count += countSymlinks(child);
            }
        }
        return count;
    }

    private static boolean isGzip(File file) {
        if (file == null || !file.isFile() || file.length() < 2) {
            return false;
        }
        try (InputStream in = new FileInputStream(file)) {
            int b0 = in.read();
            int b1 = in.read();
            return b0 == 0x1f && b1 == 0x8b;
        } catch (IOException e) {
            Log.w(TAG, "cannot read magic of " + file + ": " + e);
            return false;
        }
    }
}
