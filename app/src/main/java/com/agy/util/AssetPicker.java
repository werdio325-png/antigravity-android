package com.agy.util;

import android.content.res.AssetManager;

import java.io.IOException;
import java.io.InputStream;

/** Picks the first openable asset from an ordered candidate list. */
public final class AssetPicker {

    private AssetPicker() {
    }

    public static String pick(AssetManager assets, String... candidates) {
        for (String asset : candidates) {
            try (InputStream in = assets.open(asset)) {
                if (in != null) {
                    return asset;
                }
            } catch (IOException ignored) {
                // try the next candidate
            }
        }
        return null;
    }
}
