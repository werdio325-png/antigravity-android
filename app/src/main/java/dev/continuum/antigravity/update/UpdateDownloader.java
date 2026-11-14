package dev.continuum.antigravity.update;

import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Streams an APK from a GitHub release into app-private cache and verifies the
 * published SHA-256 before the file is ever handed to the installer.
 *
 * A download whose digest does not match the published checksum is deleted and
 * never installed.
 */
public final class UpdateDownloader {

    private static final String TAG = "UpdateDownloader";
    private static final int CONNECT_TIMEOUT_MS = 20000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int BUFFER = 64 * 1024;
    /** Hard ceiling so a malicious/odd response cannot fill the device. */
    private static final long MAX_BYTES = 900L * 1024 * 1024;

    public interface Progress {
        void onProgress(long downloaded, long total);
    }

    private UpdateDownloader() {
    }

    public static File download(UpdateInfo info, File cacheDir, Progress progress) throws IOException {
        if (info == null || info.apkUrl == null || !info.apkUrl.startsWith("https://")) {
            throw new IOException("invalid download URL");
        }
        if (!info.hasChecksum()) {
            throw new IOException("release does not publish a SHA-256 checksum");
        }
        File dir = new File(cacheDir, "updates");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("cannot create update cache directory");
        }
        File temp = new File(dir, "continuum-update.apk.part");
        File target = new File(dir, "continuum-update.apk");
        if (temp.exists() && !temp.delete()) {
            throw new IOException("cannot clear previous partial download");
        }

        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IOException("SHA-256 unavailable");
        }

        HttpURLConnection connection = null;
        long total = info.apkSize;
        try {
            connection = (HttpURLConnection) new URL(info.apkUrl).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "Continuum-Update");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                throw new IOException("HTTP " + code + " downloading update");
            }
            long declared = connection.getContentLengthLong();
            if (declared > 0) {
                total = declared;
            }
            if (total > MAX_BYTES) {
                throw new IOException("update is larger than the allowed maximum");
            }

            long downloaded = 0;
            long lastReported = 0;
            try (InputStream in = connection.getInputStream();
                 FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[BUFFER];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    downloaded += read;
                    if (downloaded > MAX_BYTES) {
                        throw new IOException("update exceeds the allowed maximum size");
                    }
                    if (progress != null && downloaded - lastReported >= 256 * 1024) {
                        lastReported = downloaded;
                        progress.onProgress(downloaded, total);
                    }
                }
                out.flush();
                out.getFD().sync();
            }
            if (progress != null) {
                progress.onProgress(downloaded, total);
            }

            String actual = hex(digest.digest());
            if (!actual.equalsIgnoreCase(info.sha256)) {
                temp.delete();
                throw new IOException("checksum mismatch: expected " + info.sha256 + ", got " + actual);
            }
            if (target.exists() && !target.delete()) {
                Log.w(TAG, "could not remove previous update file");
            }
            if (!temp.renameTo(target)) {
                throw new IOException("cannot finalize downloaded update");
            }
            Log.i(TAG, "update downloaded and verified: " + target.getName() + " (" + downloaded + " bytes)");
            return target;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
            if (temp.exists()) {
                temp.delete();
            }
        }
    }

    /** Re-verifies a previously downloaded APK against the published digest. */
    public static boolean verify(File apk, String expectedSha256) {
        if (apk == null || !apk.isFile() || expectedSha256 == null) {
            return false;
        }
        try (FileInputStream in = new FileInputStream(apk)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return hex(digest.digest()).equalsIgnoreCase(expectedSha256);
        } catch (Exception e) {
            Log.w(TAG, "verify failed: " + e.getMessage());
            return false;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.US, "%02x", value));
        }
        return builder.toString();
    }
}
