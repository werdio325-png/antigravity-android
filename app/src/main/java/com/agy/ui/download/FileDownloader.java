package com.agy.ui.download;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Base64InputStream;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.URLUtil;
import android.webkit.WebView;
import android.widget.Toast;

import com.agy.runtime.RuntimeConfig;
import com.agy.util.PathSafety;
import com.agy.util.SensitiveDataRedactor;
import com.agy.util.TrustedCoreOrigin;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Handles file downloads initiated from the WebView with explicit runtime storage
 * permission checks. Blob payloads are streamed directly to disk (or a temporary
 * cache file while awaiting permission) so large downloads never retain multi-MB
 * Base64 strings in static heap fields.
 */
public final class FileDownloader {

    private static final String TAG = "FileDownloader";
    private static final String PENDING_BLOB_DIR = "pending_downloads";
    private static final int MAX_BLOB_BYTES = 64 * 1024 * 1024;
    public static final int REQUEST_STORAGE_DOWNLOAD = 2001;

    public static class DownloadTask {
        public final String url;
        public final String userAgent;
        public final String contentDisposition;
        public final String mimeType;
        public final long contentLength;
        public final File pendingBlobFile; // temp cache file if blob download awaited permission

        public DownloadTask(String url, String userAgent, String contentDisposition,
                            String mimeType, long contentLength, File pendingBlobFile) {
            this.url = url;
            this.userAgent = userAgent;
            this.contentDisposition = contentDisposition;
            this.mimeType = mimeType;
            this.contentLength = contentLength;
            this.pendingBlobFile = pendingBlobFile;
        }
    }

    private static DownloadTask pendingTask;

    private FileDownloader() {
    }

    public static boolean hasStoragePermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    public static synchronized void clearPendingTask() {
        DownloadTask old = pendingTask;
        pendingTask = null;
        if (old != null) {
            safeDelete(old.pendingBlobFile);
        }
    }

    private static synchronized void setPendingTask(DownloadTask task) {
        DownloadTask old = pendingTask;
        pendingTask = task;
        if (old != null && old != task) {
            safeDelete(old.pendingBlobFile);
        }
    }

    private static synchronized DownloadTask takePendingTask() {
        DownloadTask task = pendingTask;
        pendingTask = null;
        return task;
    }

    public static void requestStoragePermission(Activity activity, DownloadTask task) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            if (task != null) {
                safeDelete(task.pendingBlobFile);
            }
            return;
        }
        setPendingTask(task);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Toast.makeText(activity, "Требуется доступ к файлам для скачивания", Toast.LENGTH_SHORT).show();
            activity.requestPermissions(new String[]{
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            }, REQUEST_STORAGE_DOWNLOAD);
        } else {
            DownloadTask current = takePendingTask();
            executeTask(activity, null, current);
        }
    }

    public static void onPermissionResult(Activity activity, WebView webView, int requestCode, int[] grantResults) {
        if (requestCode != REQUEST_STORAGE_DOWNLOAD) {
            return;
        }
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        DownloadTask task = takePendingTask();
        if (granted) {
            if (task != null) {
                executeTask(activity, webView, task);
            }
        } else {
            if (task != null) {
                safeDelete(task.pendingBlobFile);
            }
            if (activity != null) {
                Toast.makeText(activity, "Разрешение на доступ к файлам отклонено", Toast.LENGTH_LONG).show();
            }
        }
    }

    public static void handleDownload(Activity activity, WebView webView, String url,
                                      String userAgent, String contentDisposition,
                                      String mimeType, long contentLength) {
        DownloadTask task = new DownloadTask(url, userAgent, contentDisposition, mimeType, contentLength, null);
        if (!hasStoragePermission(activity)) {
            requestStoragePermission(activity, task);
        } else {
            executeTask(activity, webView, task);
        }
    }

    public static void handleBlobData(final Activity activity, final String base64Data,
                                      final String filename, final String mimeType) {
        if (activity == null || base64Data == null) {
            return;
        }
        if (hasStoragePermission(activity)) {
            saveBase64ToDownloads(activity, base64Data, filename, mimeType);
            return;
        }
        // Stream-decode into a temporary cache file so we never keep up to 64 MiB
        // of Base64 pinned in static heap memory across the permission dialog.
        final Context appContext = activity.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                File tempFile = null;
                try {
                    tempFile = spillBase64ToCacheFile(appContext, base64Data);
                    final File readyTempFile = tempFile;
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            if (activity.isFinishing() || activity.isDestroyed()) {
                                safeDelete(readyTempFile);
                                return;
                            }
                            DownloadTask task = new DownloadTask(
                                    null, null, filename, mimeType, readyTempFile.length(), readyTempFile);
                            if (hasStoragePermission(activity)) {
                                executeTask(activity, null, task);
                            } else {
                                requestStoragePermission(activity, task);
                            }
                        }
                    });
                } catch (final Exception e) {
                    safeDelete(tempFile);
                    Log.e(TAG, "Failed staging blob for permission request", e);
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(appContext, "Ошибка сохранения файла: " + e.getMessage(),
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }, "agy-blob-stage").start();
    }

    private static void executeTask(final Activity activity, final WebView webView, final DownloadTask task) {
        if (task == null || activity == null) {
            if (task != null) {
                safeDelete(task.pendingBlobFile);
            }
            return;
        }

        // Case 1: Staged blob cache file
        if (task.pendingBlobFile != null) {
            moveTempFileToDownloads(activity, task.pendingBlobFile, task.contentDisposition, task.mimeType);
            return;
        }

        if (task.url == null || task.url.trim().isEmpty()) {
            return;
        }

        final String rawUrl = task.url.trim();

        // Case 2: blob: URL -> extract data via JS in WebView
        if (rawUrl.startsWith("blob:") || rawUrl.startsWith("data:")) {
            if (webView != null) {
                String safeName = URLUtil.guessFileName(rawUrl, task.contentDisposition, task.mimeType);
                extractBlobViaJs(webView, rawUrl, safeName, task.mimeType);
            } else {
                Toast.makeText(activity, "Невозможно скачать blob без WebView", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        // Case 3: exact HTTPS core origin -> direct download to avoid DownloadManager
        // TLS errors. Other loopback ports are not treated as trusted core endpoints.
        Uri uri = Uri.parse(rawUrl);
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getHost().isEmpty()
                || uri.getUserInfo() != null) {
            Log.w(TAG, "refusing non-web or credential-bearing download URL");
            Toast.makeText(activity, "Недопустимый адрес загрузки", Toast.LENGTH_SHORT).show();
            return;
        }
        if (TrustedCoreOrigin.matches(uri, RuntimeConfig.httpsPort(activity))) {
            downloadDirect(activity, rawUrl, task.contentDisposition, task.mimeType);
            return;
        }

        // Case 4: Standard HTTP / HTTPS -> enqueue in system DownloadManager
        try {
            DownloadManager.Request request = new DownloadManager.Request(uri);
            if (task.mimeType != null && !task.mimeType.isEmpty()) {
                request.setMimeType(task.mimeType);
            }
            String fileName = safeFileName(
                    URLUtil.guessFileName(rawUrl, task.contentDisposition, task.mimeType));
            if (fileName.isEmpty()) {
                fileName = "download_" + System.currentTimeMillis();
            }
            request.setTitle(fileName);
            request.setDescription("Скачивание файла");
            request.allowScanningByMediaScanner();
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);

            if (task.userAgent != null && !task.userAgent.isEmpty()) {
                request.addRequestHeader("User-Agent", task.userAgent);
            }
            String cookies = CookieManager.getInstance().getCookie(rawUrl);
            if (cookies != null && !cookies.isEmpty()) {
                request.addRequestHeader("Cookie", cookies);
            }

            DownloadManager dm = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(request);
                Toast.makeText(activity, "Скачивание: " + fileName, Toast.LENGTH_SHORT).show();
                Log.i(TAG, "Download enqueued: " + fileName + " url="
                        + SensitiveDataRedactor.safeUrl(rawUrl));
            }
        } catch (Exception e) {
            Log.w(TAG, "DownloadManager failed, falling back to direct download: " + e.getMessage());
            downloadDirect(activity, rawUrl, task.contentDisposition, task.mimeType);
        }
    }

    private static void extractBlobViaJs(WebView webView, String blobUrl, String filename, String mimeType) {
        String js = "(function(blobUrl, filename, mimeType) {" +
                "  try {" +
                "    var xhr = new XMLHttpRequest();" +
                "    xhr.open('GET', blobUrl, true);" +
                "    xhr.responseType = 'blob';" +
                "    xhr.onload = function() {" +
                "      if (xhr.status === 200 || xhr.status === 0) {" +
                "        var reader = new FileReader();" +
                "        reader.onloadend = function() {" +
                "          if (window.Android && window.Android.saveBlob && window.__AG_NATIVE_BRIDGE_TOKEN__) {" +
                "            window.Android.saveBlob(window.__AG_NATIVE_BRIDGE_TOKEN__, reader.result, filename, mimeType);" +
                "          }" +
                "        };" +
                "        reader.readAsDataURL(xhr.response);" +
                "      }" +
                "    };" +
                "    xhr.send();" +
                "  } catch(e) {" +
                "    console.error('Failed to extract blob', e);" +
                "  }" +
                "})(" + JSONObject.quote(blobUrl == null ? "" : blobUrl) + ","
                + JSONObject.quote(filename == null ? "" : filename) + ","
                + JSONObject.quote(mimeType == null ? "" : mimeType) + ");";
        webView.evaluateJavascript(js, null);
    }

    public static void saveBase64ToDownloads(final Context context, final String dataUrlOrBase64,
                                             final String filename, final String mimeType) {
        final Context appContext = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                File target = null;
                try {
                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!downloadsDir.exists() && !downloadsDir.mkdirs() && !downloadsDir.isDirectory()) {
                        throw new IOException("cannot create Downloads directory");
                    }

                    String name = safeFileName(filename);
                    if (name.isEmpty()) {
                        name = "download_" + System.currentTimeMillis();
                    }
                    target = getSafeUniqueFile(downloadsDir, name);

                    streamDecodeBase64ToFile(dataUrlOrBase64, target);

                    final File savedFile = target;
                    MediaScannerConnection.scanFile(appContext, new String[]{savedFile.getAbsolutePath()}, null, null);

                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(appContext, "Сохранено в Загрузки: " + savedFile.getName(), Toast.LENGTH_LONG).show();
                        }
                    });
                    Log.i(TAG, "Blob saved to: " + savedFile.getAbsolutePath());
                } catch (final Exception e) {
                    safeDelete(target);
                    Log.e(TAG, "Failed to save blob", e);
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(appContext, "Ошибка сохранения файла: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }, "agy-blob-save").start();
    }

    private static void moveTempFileToDownloads(final Context context, final File tempFile,
                                                final String filename, final String mimeType) {
        final Context appContext = context.getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                File target = null;
                try {
                    if (tempFile == null || !tempFile.isFile()) {
                        throw new IOException("staged download file no longer exists");
                    }
                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!downloadsDir.exists() && !downloadsDir.mkdirs() && !downloadsDir.isDirectory()) {
                        throw new IOException("cannot create Downloads directory");
                    }

                    String name = safeFileName(filename);
                    if (name.isEmpty()) {
                        name = "download_" + System.currentTimeMillis();
                    }
                    target = getSafeUniqueFile(downloadsDir, name);

                    try (InputStream in = new FileInputStream(tempFile);
                         OutputStream out = new FileOutputStream(target)) {
                        byte[] buf = new byte[16 * 1024];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            out.write(buf, 0, n);
                        }
                        out.flush();
                    }

                    final File savedFile = target;
                    MediaScannerConnection.scanFile(appContext, new String[]{savedFile.getAbsolutePath()}, null, null);

                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(appContext, "Сохранено в Загрузки: " + savedFile.getName(), Toast.LENGTH_LONG).show();
                        }
                    });
                    Log.i(TAG, "Staged blob saved to: " + savedFile.getAbsolutePath());
                } catch (final Exception e) {
                    safeDelete(target);
                    Log.e(TAG, "Failed to move staged blob", e);
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(appContext, "Ошибка сохранения файла: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                } finally {
                    safeDelete(tempFile);
                }
            }
        }, "agy-blob-move").start();
    }

    private static File spillBase64ToCacheFile(Context context, String dataUrlOrBase64) throws IOException {
        File dir = new File(context.getCacheDir(), PENDING_BLOB_DIR);
        if (!dir.exists() && !dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("cannot create pending downloads cache directory");
        }
        File tempFile = File.createTempFile("blob_", ".tmp", dir);
        try {
            streamDecodeBase64ToFile(dataUrlOrBase64, tempFile);
            return tempFile;
        } catch (IOException e) {
            safeDelete(tempFile);
            throw e;
        }
    }

    private static void streamDecodeBase64ToFile(String dataUrlOrBase64, File target) throws IOException {
        if (dataUrlOrBase64 == null) {
            throw new IOException("empty blob payload");
        }
        int start = 0;
        int comma = dataUrlOrBase64.indexOf(',');
        if (comma >= 0) {
            start = comma + 1;
        }
        long estimatedBytes = ((long) (dataUrlOrBase64.length() - start) * 3L) / 4L;
        if (estimatedBytes > MAX_BLOB_BYTES + 1024L) {
            throw new IOException("blob exceeds the 64 MiB download limit");
        }

        long written = 0L;
        try (InputStream raw = new AsciiSubstringInputStream(dataUrlOrBase64, start);
             Base64InputStream decoded = new Base64InputStream(raw, Base64.DEFAULT);
             FileOutputStream fos = new FileOutputStream(target)) {
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = decoded.read(buf)) != -1) {
                written += n;
                if (written > MAX_BLOB_BYTES) {
                    throw new IOException("blob exceeds the 64 MiB download limit");
                }
                fos.write(buf, 0, n);
            }
            fos.flush();
        }
    }

    /** Zero-copy InputStream over the ASCII Base64 slice of a String. */
    private static final class AsciiSubstringInputStream extends InputStream {
        private final String src;
        private final int length;
        private int pos;

        AsciiSubstringInputStream(String src, int startOffset) {
            this.src = src;
            this.length = src.length();
            this.pos = Math.max(0, Math.min(startOffset, this.length));
        }

        @Override
        public int read() {
            if (pos >= length) {
                return -1;
            }
            return src.charAt(pos++) & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) {
            if (pos >= length) {
                return -1;
            }
            int avail = Math.min(len, length - pos);
            for (int i = 0; i < avail; i++) {
                b[off + i] = (byte) (src.charAt(pos++) & 0xFF);
            }
            return avail;
        }
    }

    private static void downloadDirect(final Activity activity, final String fileUrl,
                                       final String contentDisposition, final String mimeType) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    URL u = new URL(fileUrl);
                    HttpURLConnection conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 Antigravity");
                    conn.connect();

                    if (conn.getResponseCode() >= 400) {
                        throw new RuntimeException("HTTP " + conn.getResponseCode());
                    }

                    String disp = conn.getHeaderField("Content-Disposition");
                    if (disp == null || disp.isEmpty()) {
                        disp = contentDisposition;
                    }
                    String type = conn.getContentType();
                    if (type == null || type.isEmpty()) {
                        type = mimeType;
                    }

                    String fileName = safeFileName(URLUtil.guessFileName(fileUrl, disp, type));
                    if (fileName.isEmpty()) {
                        fileName = "download_" + System.currentTimeMillis();
                    }
                    File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    if (!downloadsDir.exists() && !downloadsDir.mkdirs() && !downloadsDir.isDirectory()) {
                        throw new IOException("cannot create Downloads directory");
                    }

                    File target = getSafeUniqueFile(downloadsDir, fileName);

                    try (InputStream in = conn.getInputStream();
                         OutputStream out = new FileOutputStream(target)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                        out.flush();
                    }

                    final File savedFile = target;
                    MediaScannerConnection.scanFile(activity, new String[]{savedFile.getAbsolutePath()}, null, null);

                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(activity, "Сохранено в Загрузки: " + savedFile.getName(), Toast.LENGTH_LONG).show();
                        }
                    });
                    Log.i(TAG, "Direct download saved: " + savedFile.getAbsolutePath());
                } catch (final Exception e) {
                    Log.e(TAG, "Direct download failed", e);
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(activity, "Ошибка скачивания: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }).start();
    }

    private static String safeFileName(String filename) {
        if (filename == null) {
            return "";
        }
        String name = filename.trim().replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        StringBuilder clean = new StringBuilder(Math.min(name.length(), 180));
        for (int i = 0; i < name.length() && clean.length() < 180; i++) {
            char ch = name.charAt(i);
            if (ch < 0x20 || ch == 0x7f) {
                clean.append('_');
            } else if (ch != '/' && ch != '\\') {
                clean.append(ch);
            }
        }
        name = clean.toString().trim();
        return ".".equals(name) || "..".equals(name) ? "" : name;
    }

    private static File getSafeUniqueFile(File downloadsDir, String requestedName) throws IOException {
        File root = downloadsDir.getCanonicalFile();
        String name = safeFileName(requestedName);
        if (name.isEmpty()) {
            throw new IOException("invalid download filename");
        }
        File target = new File(root, name).getCanonicalFile();
        if (target.equals(root) || !PathSafety.isInside(root, target)) {
            throw new IOException("download path escaped Downloads directory");
        }
        File unique = getUniqueFile(target);
        if (unique.equals(root) || !PathSafety.isInside(root, unique)) {
            throw new IOException("download path escaped Downloads directory");
        }
        return unique;
    }

    private static File getUniqueFile(File file) {
        if (!file.exists()) {
            return file;
        }
        String parent = file.getParent();
        String name = file.getName();
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int count = 1;
        while (file.exists()) {
            file = new File(parent, base + " (" + count + ")" + ext);
            count++;
        }
        return file;
    }

    private static void safeDelete(File file) {
        if (file != null && file.exists()) {
            try {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            } catch (Exception ignored) {
            }
        }
    }
}
