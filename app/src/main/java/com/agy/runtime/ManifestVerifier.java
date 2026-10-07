package com.agy.runtime;

import android.content.Context;

import com.agy.util.IoUtil;
import com.agy.util.Sha256;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.util.Iterator;

/** Verifies every runtime file against assets/runtime/manifest.json (fail-closed). */
public final class ManifestVerifier {

    private ManifestVerifier() {
    }

    /** Returns null on success, or a human-readable reason on the first failure. */
    public static String verifyError(Context appContext, File runtimeDir) {
        try (InputStream raw = appContext.getAssets().open(RuntimePaths.MANIFEST_ASSET)) {
            JSONObject root = new JSONObject(IoUtil.readAll(raw, "UTF-8"));
            JSONObject files = root.optJSONObject("files");
            if (files == null) {
                files = root; // accept a flat {path: sha256} object too
            }
            Iterator<String> keys = files.keys();
            while (keys.hasNext()) {
                String relative = keys.next();
                if (relative.endsWith("manifest.json")) {
                    continue;
                }
                String expected = files.optString(relative, null);
                File target = new File(runtimeDir, relative);
                if (expected == null || !target.isFile()) {
                    return "missing runtime file: " + relative;
                }
                String actual = Sha256.of(target);
                if (!expected.equalsIgnoreCase(actual)) {
                    return "hash mismatch for " + relative
                            + " (expected=" + expected + " actual=" + actual + ")";
                }
            }
            return null;
        } catch (Exception e) {
            return "manifest unreadable: " + e.getMessage();
        }
    }
}
