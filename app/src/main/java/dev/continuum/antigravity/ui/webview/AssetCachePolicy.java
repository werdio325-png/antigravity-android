package dev.continuum.antigravity.ui.webview;

import java.util.Map;

/**
 * Cache policy for the WebView's static assets.
 *
 * The UI files are served under stable names ({@code main.js}, {@code
 * index.html}) from app-private storage, so they must never be treated as
 * immutable: doing so let the WebView keep serving the previous release's bundle
 * after an update, which is one of the ways the app appeared to show stale UI.
 *
 * Policy: every response carries a strong-ish validator (size + mtime) and
 * {@code no-cache}, so the WebView revalidates with us on each load. Revalidation
 * is a local file stat, and a match returns 304, so nothing is re-downloaded.
 */
public final class AssetCachePolicy {

    static final String CACHE_CONTROL = "no-cache, must-revalidate";

    private AssetCachePolicy() {
    }

    /** Validator for a local file, e.g. {@code "1720950000000-216196"}. */
    public static String etagFor(long lastModified, long length) {
        return "W/\"" + lastModified + "-" + length + "\"";
    }

    /**
     * True when the client already has this exact version.
     * Accepts a comma-separated list and the {@code W/} weak prefix.
     */
    public static boolean matchesEtag(String ifNoneMatch, String etag) {
        if (ifNoneMatch == null || etag == null) {
            return false;
        }
        String normalizedEtag = stripWeak(etag);
        for (String candidate : ifNoneMatch.split(",")) {
            String value = candidate.trim();
            if ("*".equals(value) || stripWeak(value).equals(normalizedEtag)) {
                return true;
            }
        }
        return false;
    }

    /** Case-insensitive header lookup; WebView header casing is not guaranteed. */
    public static String header(Map<String, String> headers, String name) {
        if (headers == null || name == null) {
            return null;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String stripWeak(String value) {
        String trimmed = value.trim();
        return trimmed.startsWith("W/") ? trimmed.substring(2) : trimmed;
    }
}
