package com.agy.install;

import android.content.res.AssetManager;
import android.util.Log;

import com.agy.util.AssetUnpacker;
import com.agy.util.IoUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Stages the bootstrap archive and extracts it (toybox tar, then Java fallback). */
public final class BootstrapExtractor {

    private static final String TAG = "BootstrapExtractor";

    private BootstrapExtractor() {
    }

    public static void extract(AssetManager assets, String asset, File prefix) {
        File tmp = null;
        try {
            tmp = File.createTempFile("bootstrap", ".tar", prefix);
            AssetUnpacker.copyFile(assets, asset, tmp);
        } catch (IOException e) {
            IoUtil.deleteQuietly(tmp);
            throw new IllegalStateException("cannot stage " + asset + ": " + e, e);
        }

        // Name is not trusted: pick gzip vs plain tar from the magic bytes.
        boolean gzip = isGzip(tmp);
        Log.i(TAG, "bootstrap " + asset + " format=" + (gzip ? "gzip" : "plain-tar"));

        try {
            if (TarRunner.runToyboxTar(tmp, prefix, gzip) && new File(prefix, "bin").isDirectory()) {
                Log.i(TAG, "bootstrap extracted with toybox tar");
                return;
            }
            Log.w(TAG, "toybox tar unavailable or failed; Java fallback (symlinks may be missing)");
            try (InputStream in = new FileInputStream(tmp)) {
                if (gzip) {
                    TarExtractor.untarGz(in, prefix);
                } else {
                    TarExtractor.untar(in, prefix);
                }
            }
            Log.w(TAG, "Java fallback extraction complete; symlinks may be missing");
        } catch (IOException e) {
            throw new IllegalStateException("bootstrap extraction failed: " + e.getMessage(), e);
        } finally {
            IoUtil.deleteQuietly(tmp);
        }
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
