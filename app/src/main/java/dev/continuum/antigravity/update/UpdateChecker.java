package dev.continuum.antigravity.update;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;

import dev.continuum.antigravity.BuildConfig;

/**
 * Reads the fork's GitHub Releases feed.
 *
 * Only https://api.github.com and release asset URLs returned by it are used.
 * Nothing is downloaded or executed here; the checksum is taken from the
 * release's published SHA256SUMS asset so it is verified independently of the
 * APK download itself.
 */
public final class UpdateChecker {

    private static final String TAG = "UpdateChecker";
    private static final String API_BASE = "https://api.github.com/repos/";
    private static final int MAX_JSON_BYTES = 2 * 1024 * 1024;
    private static final int TIMEOUT_MS = 15000;

    private UpdateChecker() {
    }

    public static boolean isConfigured() {
        return BuildConfig.UPDATE_REPO != null && BuildConfig.UPDATE_REPO.contains("/");
    }

    public static String feedUrl() {
        return API_BASE + BuildConfig.UPDATE_REPO + "/releases/latest";
    }

    public static UpdateInfo fetchLatest(Context context) throws IOException {
        if (!isConfigured()) {
            throw new IOException("update feed is not configured (UPDATE_REPO)");
        }
        String body = httpGetJson(feedUrl());
        try {
            JSONObject release = new JSONObject(body);
            String tag = release.optString("tag_name", "");
            if (tag.isEmpty()) {
                throw new IOException("release has no tag_name");
            }
            String version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
            String notes = release.optString("body", "");
            String published = release.optString("published_at", "");

            JSONObject apkAsset = null;
            JSONObject sumsAsset = null;
            JSONArray assets = release.optJSONArray("assets");
            for (int i = 0; assets != null && i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) {
                    continue;
                }
                String name = asset.optString("name", "");
                if (apkAsset == null && name.equalsIgnoreCase(BuildConfig.UPDATE_ASSET)) {
                    apkAsset = asset;
                }
                if (sumsAsset == null && name.equalsIgnoreCase(BuildConfig.UPDATE_CHECKSUMS_ASSET)) {
                    sumsAsset = asset;
                }
            }
            if (apkAsset == null) {
                // Tolerate a renamed asset as long as it is an APK.
                for (int i = 0; assets != null && i < assets.length(); i++) {
                    JSONObject asset = assets.optJSONObject(i);
                    if (asset != null && asset.optString("name", "").toLowerCase(Locale.US).endsWith(".apk")) {
                        apkAsset = asset;
                        break;
                    }
                }
            }
            if (apkAsset == null) {
                throw new IOException("release " + tag + " has no APK asset");
            }

            String sha256 = null;
            if (sumsAsset != null) {
                try {
                    sha256 = findChecksum(httpGetText(sumsAsset.optString("browser_download_url", "")),
                            apkAsset.optString("name", ""));
                } catch (Exception e) {
                    Log.w(TAG, "cannot read published checksums: " + e.getMessage());
                }
            }

            return new UpdateInfo(
                    tag,
                    version,
                    notes,
                    apkAsset.optString("browser_download_url", ""),
                    apkAsset.optString("name", BuildConfig.UPDATE_ASSET),
                    apkAsset.optLong("size", 0L),
                    sha256,
                    published);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("malformed release response: " + e.getMessage(), e);
        }
    }

    /** Parses "sha256  filename" lines (sha256sum format). */
    static String findChecksum(String sums, String assetName) {
        if (sums == null) {
            return null;
        }
        for (String line : sums.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String[] parts = trimmed.split("\\s+", 2);
            if (parts.length == 2 && parts[0].matches("[0-9a-fA-F]{64}")) {
                String name = parts[1].trim();
                if (name.startsWith("*")) {
                    name = name.substring(1);
                }
                if (name.equals(assetName) || name.endsWith("/" + assetName)) {
                    return parts[0].toLowerCase(Locale.US);
                }
            }
        }
        return null;
    }

    private static String httpGetJson(String url) throws IOException {
        return httpGetText(url);
    }

    private static String httpGetText(String url) throws IOException {
        if (url == null || !url.startsWith("https://")) {
            throw new IOException("refusing non-https URL");
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("User-Agent", "Continuum/" + BuildConfig.APP_VERSION_NAME);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " from " + url);
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    if (out.size() > MAX_JSON_BYTES) {
                        throw new IOException("response too large");
                    }
                }
                return out.toString("UTF-8");
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }
}
