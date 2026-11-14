package dev.continuum.antigravity.update;

/** One GitHub release that carries an installable APK for this fork. */
public final class UpdateInfo {

    public final String tagName;
    public final String versionName;
    public final String notes;
    public final String apkUrl;
    public final String apkName;
    public final long apkSize;
    public final String sha256;
    public final String publishedAt;

    public UpdateInfo(String tagName, String versionName, String notes, String apkUrl,
                      String apkName, long apkSize, String sha256, String publishedAt) {
        this.tagName = tagName;
        this.versionName = versionName;
        this.notes = notes == null ? "" : notes;
        this.apkUrl = apkUrl;
        this.apkName = apkName;
        this.apkSize = apkSize;
        this.sha256 = sha256;
        this.publishedAt = publishedAt == null ? "" : publishedAt;
    }

    public boolean hasChecksum() {
        return sha256 != null && sha256.matches("[0-9a-fA-F]{64}");
    }

    /** Numeric comparison of dotted versions; suffixes are ignored. */
    public static int compareVersions(String left, String right) {
        int[] a = parts(left);
        int[] b = parts(right);
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return x < y ? -1 : 1;
            }
        }
        return 0;
    }

    private static int[] parts(String version) {
        if (version == null) {
            return new int[0];
        }
        String cleaned = version.trim();
        if (cleaned.startsWith("v") || cleaned.startsWith("V")) {
            cleaned = cleaned.substring(1);
        }
        String[] segments = cleaned.split("[^0-9]+");
        int[] out = new int[Math.min(segments.length, 4)];
        for (int i = 0; i < out.length; i++) {
            try {
                out[i] = segments[i].isEmpty() ? 0 : Integer.parseInt(segments[i]);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
