package com.agy.oauth;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;

/** Null-safe handle to the core's persisted OAuth token file. */
public final class OAuthTokenStore {

    /** Longest token snapshot we inspect; the real file is a small JSON blob. */
    private static final int MAX_SCAN = 8192;

    private OAuthTokenStore() {
    }

    public static File tokenFile(Context context) {
        if (context == null) {
            return null;
        }
        return new File(new File(context.getFilesDir(), OAuthConstants.TOKEN_DIR),
                OAuthConstants.TOKEN_NAME);
    }

    /**
     * True once the core has persisted a REAL token. The core first writes the
     * placeholder {@code {"token":null}} while the login is in flight and only
     * later overwrites it with the exchanged token, so a mere non-empty check
     * would report success too early and the UI would reload back to login.
     * Callers must poll this.
     */
    public static boolean hasToken(Context context) {
        File file = tokenFile(context);
        if (file == null || !file.isFile() || file.length() == 0) {
            return false;
        }
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[(int) Math.min(file.length(), MAX_SCAN)];
            int n = in.read(buf);
            if (n <= 0) {
                return false;
            }
            String text = new String(buf, 0, n, StandardCharsets.UTF_8);
            return !isNullOrBlankToken(text);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * True when {@code text} is missing a token or carries an explicit JSON
     * {@code null} for it (the core's not-logged-in placeholder).
     */
    private static boolean isNullOrBlankToken(String text) {
        if (text == null) {
            return true;
        }
        String t = text.trim();
        if (t.isEmpty()) {
            return true;
        }
        int at = t.indexOf("\"token\"");
        if (at < 0) {
            return false;
        }
        int colon = t.indexOf(':', at);
        if (colon < 0) {
            return false;
        }
        int i = colon + 1;
        while (i < t.length() && Character.isWhitespace(t.charAt(i))) {
            i++;
        }
        return t.startsWith("null", i);
    }
}
