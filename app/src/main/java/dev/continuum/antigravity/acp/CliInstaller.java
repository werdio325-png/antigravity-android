package dev.continuum.antigravity.acp;

import android.content.Context;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

import dev.continuum.antigravity.install.TarExtractor;

/**
 * Installs Google's Antigravity CLI from Google's own distribution endpoint.
 *
 * Mirrors the official installer's contract: query the published manifest, refuse
 * anything that is not https from Google's CLI host, require the manifest's
 * SHA-512 to match the downloaded archive, then unpack the single binary. No
 * mirror, no bundling, no modified build, and nothing is executed before the
 * digest matches.
 */
public final class CliInstaller {

    private static final String TAG = "CliInstaller";
    private static final int TIMEOUT_MS = 30000;
    private static final long MAX_BYTES = 400L * 1024 * 1024;

    public interface Progress {
        void onProgress(long downloaded, long total);
    }

    public static final class Result {
        public final boolean ok;
        public final String version;
        public final File binary;
        public final String error;

        Result(boolean ok, String version, File binary, String error) {
            this.ok = ok;
            this.version = version;
            this.binary = binary;
            this.error = error;
        }
    }

    private CliInstaller() {
    }

    public static File binaryPath(Context context) {
        return new File(context.getFilesDir(), "bin/agy");
    }

    public static String installedVersion(Context context) {
        File marker = new File(context.getFilesDir(), "bin/agy.version");
        if (!marker.isFile()) {
            return null;
        }
        try {
            return readText(marker).trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** Downloads and installs the CLI if the published version is newer. */
    public static Result install(Context context, boolean force, Progress progress) {
        try {
            String manifest = httpText(CliRelease.manifestUrl());
            CliRelease release = CliRelease.parse(manifest);
            if (!release.isTrustedPayload()) {
                return new Result(false, null, null, "CLI payload host is not Google's distribution host");
            }
            String installed = installedVersion(context);
            File target = binaryPath(context);
            if (!force && installed != null && !CliRelease.isNewer(release.version, installed)
                    && target.isFile()) {
                return new Result(true, installed, target, null);
            }

            File staging = new File(context.getCacheDir(), "agy-download.tmp");
            long total = download(release.url, staging, progress);
            if (total > MAX_BYTES) {
                staging.delete();
                return new Result(false, null, null, "CLI archive is larger than the allowed maximum");
            }
            String digest = sha512(staging);
            if (!digest.equalsIgnoreCase(release.sha512)) {
                staging.delete();
                return new Result(false, null, null,
                        "SHA-512 mismatch: expected " + release.sha512 + ", got " + digest);
            }

            File dir = target.getParentFile();
            if (dir == null || (!dir.isDirectory() && !dir.mkdirs())) {
                return new Result(false, null, null, "cannot create CLI directory");
            }
            File unpacked = unpackSingleBinary(staging, dir);
            staging.delete();
            if (unpacked == null) {
                return new Result(false, null, null, "CLI archive did not contain a binary");
            }
            if (!unpacked.renameTo(target) && !target.isFile()) {
                return new Result(false, null, null, "cannot place the CLI binary");
            }
            if (!target.setExecutable(true, true)) {
                Log.w(TAG, "cannot set execute bit on " + target);
            }
            writeText(new File(dir, "agy.version"), release.version + "\n");
            Log.i(TAG, "installed official CLI " + release.version + " (" + digest + ")");
            return new Result(true, release.version, target, null);
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Log.w(TAG, "CLI install failed: " + message);
            return new Result(false, null, null, message);
        }
    }

    private static File unpackSingleBinary(File archive, File dir) throws Exception {
        // The published asset is a .tar.gz containing "antigravity".
        try (InputStream in = new java.io.FileInputStream(archive)) {
            TarExtractor.untarGz(in, dir);
        }
        return new File(dir, "antigravity").isFile()
                ? new File(dir, "antigravity")
                : (new File(dir, "agy").isFile() ? new File(dir, "agy") : null);
    }

    private static long download(String url, File destination, Progress progress) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Continuum-CLI-Installer");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new java.io.IOException("HTTP " + code + " downloading the CLI");
            }
            long total = connection.getContentLengthLong();
            long done = 0;
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    done += read;
                    if (done > MAX_BYTES) {
                        throw new java.io.IOException("CLI archive exceeds the allowed size");
                    }
                    if (progress != null) {
                        progress.onProgress(done, total);
                    }
                }
                out.getFD().sync();
            }
            return done;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String httpText(String url) throws Exception {
        if (!url.startsWith("https://")) {
            throw new java.io.IOException("refusing non-https manifest URL");
        }
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "Continuum-CLI-Installer");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new java.io.IOException("HTTP " + code + " fetching the CLI manifest");
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    if (out.size() > 1024 * 1024) {
                        throw new java.io.IOException("manifest too large");
                    }
                }
                return out.toString("UTF-8");
            }
        } finally {
            connection.disconnect();
        }
    }

    static String sha512(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-512");
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder builder = new StringBuilder(128);
        for (byte value : digest.digest()) {
            builder.append(String.format(Locale.US, "%02x", value));
        }
        return builder.toString();
    }

    private static String readText(File file) throws Exception {
        try (InputStream in = new java.io.FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        }
    }

    private static void writeText(File file, String text) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(text.getBytes("UTF-8"));
        }
    }
}
