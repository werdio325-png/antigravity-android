package com.agy.util;

import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Recursively copies an asset tree onto the filesystem. */
public final class AssetUnpacker {

    private AssetUnpacker() {
    }

    /**
     * Copies {@code assetDir} into {@code dest}. A leaf asset (no children) is
     * written as a file; a directory is created and recursed. Mirrors the
     * structure-driven walk used by both the runtime and env installers.
     */
    public static void copyTree(AssetManager assets, String assetDir, File dest) throws IOException {
        String[] children = assets.list(assetDir);
        if (children == null || children.length == 0) {
            copyFile(assets, assetDir, dest);
            return;
        }
        if (!dest.isDirectory() && !dest.mkdirs() && !dest.isDirectory()) {
            throw new IOException("cannot create " + dest);
        }
        for (String child : children) {
            copyTree(assets, assetDir + "/" + child, new File(dest, child));
        }
    }

    /**
     * Top-level unpack: refuses an empty/missing asset tree (fail-closed), then
     * delegates to {@link #copyTree}.
     */
    public static void unpackTree(AssetManager assets, String assetDir, File destDir)
            throws IOException {
        String[] children = assets.list(assetDir);
        if (children == null || children.length == 0) {
            throw new IOException("asset tree missing or empty: " + assetDir);
        }
        if (!destDir.exists() && !destDir.mkdirs()) {
            throw new IOException("cannot create " + destDir);
        }
        copyTree(assets, assetDir, destDir);
    }

    public static void copyFile(AssetManager assets, String assetPath, File dest)
            throws IOException {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        try (InputStream in = assets.open(assetPath);
             OutputStream out = new FileOutputStream(dest)) {
            IoUtil.pipe(in, out);
        }
    }
}
