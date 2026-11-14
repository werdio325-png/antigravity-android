package dev.continuum.antigravity.acp;

import org.json.JSONObject;

import java.util.Locale;

/**
 * One published Antigravity CLI release, as described by Google's official
 * updater manifest.
 *
 * The CLI is never bundled or mirrored by this app: the manifest URL and payload
 * host below are the ones Google's own installer uses, and the payload must match
 * the published SHA-512 before anything is installed or executed.
 */
public final class CliRelease {

    public static final String MANIFEST_BASE =
            "https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/";
    /** Google's public CLI storage bucket, as used by the official installer. */
    public static final String ALLOWED_PAYLOAD_HOST = "storage.googleapis.com";
    public static final String ALLOWED_MANIFEST_HOST =
            "antigravity-cli-auto-updater-974169037036.us-central1.run.app";

    public final String version;
    public final String url;
    public final String sha512;

    public CliRelease(String version, String url, String sha512) {
        this.version = version;
        this.url = url;
        this.sha512 = sha512;
    }

    /** Android arm64 is the only platform this app installs for. */
    public static String manifestUrl() {
        return MANIFEST_BASE + "android_arm64.json";
    }

    public static CliRelease parse(String json) throws IllegalArgumentException {
        try {
            JSONObject object = new JSONObject(json);
            String version = object.optString("version", "");
            String url = object.optString("url", "");
            String sha = object.optString("sha512", "").toLowerCase(Locale.US);
            if (version.isEmpty()) {
                throw new IllegalArgumentException("manifest has no version");
            }
            if (!url.startsWith("https://")) {
                throw new IllegalArgumentException("manifest URL is not https");
            }
            if (!sha.matches("[0-9a-f]{128}")) {
                throw new IllegalArgumentException("manifest sha512 is not a 512-bit hex digest");
            }
            return new CliRelease(version, url, sha);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("malformed CLI manifest: " + e.getMessage());
        }
    }

    /** Refuses payloads that do not come from Google's CLI distribution host. */
    public boolean isTrustedPayload() {
        return hostOf(url).equals(ALLOWED_PAYLOAD_HOST);
    }

    static String hostOf(String url) {
        try {
            String withoutScheme = url.substring(url.indexOf("://") + 3);
            int slash = withoutScheme.indexOf('/');
            String host = slash > 0 ? withoutScheme.substring(0, slash) : withoutScheme;
            int port = host.indexOf(':');
            return (port > 0 ? host.substring(0, port) : host).toLowerCase(Locale.US);
        } catch (Exception e) {
            return "";
        }
    }

    /** Compares dotted versions; returns true when {@code candidate} is newer. */
    public static boolean isNewer(String candidate, String installed) {
        int[] a = parts(candidate);
        int[] b = parts(installed);
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int left = i < a.length ? a[i] : 0;
            int right = i < b.length ? b[i] : 0;
            if (left != right) {
                return left > right;
            }
        }
        return false;
    }

    private static int[] parts(String version) {
        if (version == null) {
            return new int[0];
        }
        String[] split = version.trim().split("[^0-9]+");
        int[] out = new int[split.length];
        for (int i = 0; i < split.length; i++) {
            try {
                out[i] = split[i].isEmpty() ? 0 : Integer.parseInt(split[i]);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
