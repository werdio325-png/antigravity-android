package com.agy.util;

import android.content.Context;

import java.io.InputStream;

/** Reads an asset into memory as a string. Returns null on any failure. */
public final class AssetReader {

    private AssetReader() {
    }

    public static String readString(Context context, String assetPath) {
        try (InputStream in = context.getAssets().open(assetPath)) {
            return IoUtil.readAll(in, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }
}
