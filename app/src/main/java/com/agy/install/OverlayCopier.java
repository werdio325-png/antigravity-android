package com.agy.install;

import android.content.res.AssetManager;

import com.agy.util.AssetUnpacker;

import java.io.File;
import java.io.IOException;

/** Copies asset overlay/pkg/bus trees over the install prefix (fail-closed). */
public final class OverlayCopier {

    private OverlayCopier() {
    }

    /** Overlay is required: a missing/empty asset tree throws. */
    public static void overlay(AssetManager assets, String assetDir, File prefix) {
        String[] children = list(assets, assetDir);
        if (children == null || children.length == 0) {
            throw new IllegalStateException("missing/empty asset tree: " + assetDir);
        }
        copyTree(assets, assetDir, prefix);
    }

    public static void copyTree(AssetManager assets, String assetDir, File dest) {
        try {
            AssetUnpacker.copyTree(assets, assetDir, dest);
        } catch (IOException e) {
            throw new IllegalStateException("cannot copy asset tree " + assetDir + ": "
                    + e.getMessage(), e);
        }
    }

    public static void copyFile(AssetManager assets, String asset, File dest) {
        try {
            AssetUnpacker.copyFile(assets, asset, dest);
        } catch (IOException e) {
            throw new IllegalStateException("cannot copy asset " + asset + " -> " + dest, e);
        }
    }

    /** Lists an asset dir, wrapping IOException the way the installer expects. */
    public static String[] list(AssetManager assets, String assetDir) {
        try {
            return assets.list(assetDir);
        } catch (IOException e) {
            throw new IllegalStateException("cannot list asset " + assetDir + ": " + e.getMessage(), e);
        }
    }
}
