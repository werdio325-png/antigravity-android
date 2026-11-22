package com.agy.ui.extra;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;
import android.webkit.MimeTypeMap;
import android.webkit.WebView;

import com.agy.ui.MainActivity;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Safely reads pasted/IME-committed image URIs (max 12 MiB) from Android's
 * ClipboardManager or InputContentInfo and delivers Base64 payloads to WebView.
 */
public final class ClipboardImageReader {

    private static final String TAG = "ClipboardImageReader";
    public static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024; // 12 MiB

    public static final class ImageRef {
        public final Uri uri;
        public final String mime;

        public ImageRef(Uri uri, String mime) {
            this.uri = uri;
            this.mime = mime;
        }
    }

    public static final class ImagePayload {
        public final String base64;
        public final String mime;
        public final String name;

        public ImagePayload(String base64, String mime, String name) {
            this.base64 = base64;
            this.mime = mime;
            this.name = name;
        }
    }

    private ClipboardImageReader() {
    }

    public static boolean hasClipboardImage(Context context) {
        return findPrimaryClipboardImage(context) != null;
    }

    public static String readClipboardImageJson(Context context) {
        try {
            ImageRef ref = findPrimaryClipboardImage(context);
            if (ref == null) {
                return errorJson("no_clipboard_image");
            }
            ImagePayload payload = readImageFromUri(context, ref.uri, ref.mime);
            if (payload == null) {
                return errorJson("read_failed");
            }
            JSONObject obj = new JSONObject();
            obj.put("ok", true);
            obj.put("mime", payload.mime);
            obj.put("name", payload.name);
            obj.put("base64", payload.base64);
            return obj.toString();
        } catch (Exception e) {
            Log.w(TAG, "readClipboardImageJson failed: " + e.getMessage());
            return errorJson(e.getMessage() != null ? e.getMessage() : "clipboard_error");
        }
    }

    public static ImageRef findPrimaryClipboardImage(final Context context) {
        if (context == null) {
            return null;
        }
        final Context appCtx = context.getApplicationContext();
        // ClipboardManager requires a Looper thread on some Android ROMs; hop to
        // main thread just to inspect primary clip metadata (<1ms).
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return findPrimaryClipboardImageOnMain(appCtx);
        }
        final AtomicReference<ImageRef> result = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    result.set(findPrimaryClipboardImageOnMain(appCtx));
                } catch (Throwable t) {
                    Log.w(TAG, "clipboard inspect failed: " + t.getMessage());
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            latch.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return result.get();
    }

    private static ImageRef findPrimaryClipboardImageOnMain(Context appCtx) {
        Context target = MainActivity.activeActivity != null ? MainActivity.activeActivity : appCtx;
        ClipboardManager cm = (ClipboardManager) target.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) {
            return null;
        }
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return null;
        }
        ClipDescription desc = clip.getDescription();
        for (int i = 0; i < clip.getItemCount(); i++) {
            ClipData.Item item = clip.getItemAt(i);
            if (item == null || item.getUri() == null) {
                continue;
            }
            Uri uri = item.getUri();
            String mime = detectImageMime(target, uri, desc);
            if (mime != null) {
                return new ImageRef(uri, mime);
            }
        }
        return null;
    }

    public static String detectImageMime(Context context, Uri uri, ClipDescription desc) {
        if (uri == null) {
            return null;
        }
        if (context != null && "content".equalsIgnoreCase(uri.getScheme())) {
            try {
                String resolved = context.getContentResolver().getType(uri);
                if (isConcreteImageMime(resolved)) {
                    return normalizeMime(resolved);
                }
            } catch (Exception ignored) {
            }
        }
        String wildcardFallback = null;
        if (desc != null) {
            int count = desc.getMimeTypeCount();
            for (int i = 0; i < count; i++) {
                String m = desc.getMimeType(i);
                if (isConcreteImageMime(m)) {
                    return normalizeMime(m);
                }
                if (isImageMime(m)) {
                    wildcardFallback = "image/png";
                }
            }
        }
        String extMime = guessImageMimeFromUri(uri);
        if (extMime != null) {
            return extMime;
        }
        return wildcardFallback;
    }

    public static boolean isImageMime(String mime) {
        return mime != null && mime.trim().toLowerCase(Locale.US).startsWith("image/");
    }

    private static boolean isConcreteImageMime(String mime) {
        if (!isImageMime(mime)) {
            return false;
        }
        String lower = mime.trim().toLowerCase(Locale.US);
        return !"image/*".equals(lower);
    }

    private static String normalizeMime(String mime) {
        String trimmed = mime.trim().toLowerCase(Locale.US);
        int semi = trimmed.indexOf(';');
        if (semi >= 0) {
            trimmed = trimmed.substring(0, semi).trim();
        }
        return "image/*".equals(trimmed) ? "image/png" : trimmed;
    }

    private static String guessImageMimeFromUri(Uri uri) {
        String path = uri.getLastPathSegment();
        if (path == null) {
            path = uri.getPath();
        }
        if (path == null) {
            return null;
        }
        String lower = path.toLowerCase(Locale.US);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".bmp")) return "image/bmp";
        if (lower.endsWith(".heic") || lower.endsWith(".heif")) return "image/heic";
        return null;
    }

    public static ImagePayload readImageFromUri(Context context, Uri uri, String mimeHint) throws IOException {
        if (context == null || uri == null) {
            throw new IOException("missing image URI");
        }
        String scheme = uri.getScheme();
        if (!"content".equalsIgnoreCase(scheme) && !"file".equalsIgnoreCase(scheme)) {
            throw new IOException("unsupported image URI scheme");
        }

        String resolvedMime = null;
        if ("content".equalsIgnoreCase(scheme)) {
            try {
                resolvedMime = context.getContentResolver().getType(uri);
            } catch (Exception ignored) {
            }
        }
        if (!isConcreteImageMime(resolvedMime)) {
            if (isConcreteImageMime(mimeHint)) {
                resolvedMime = normalizeMime(mimeHint);
            } else {
                String guessed = guessImageMimeFromUri(uri);
                resolvedMime = guessed != null ? guessed : "image/png";
            }
        } else {
            resolvedMime = normalizeMime(resolvedMime);
        }

        byte[] bytes;
        try (InputStream in = openSafeStream(context, uri)) {
            if (in == null) {
                throw new IOException("cannot open image stream");
            }
            bytes = readBounded(in, MAX_IMAGE_BYTES);
        }
        if (bytes.length == 0) {
            throw new IOException("empty image");
        }

        String name = resolveFileName(context, uri, resolvedMime);
        String base64 = Base64.encodeToString(bytes, Base64.NO_WRAP);
        return new ImagePayload(base64, resolvedMime, name);
    }

    private static InputStream openSafeStream(Context context, Uri uri) throws IOException {
        String scheme = uri.getScheme();
        if ("content".equalsIgnoreCase(scheme)) {
            return context.getContentResolver().openInputStream(uri);
        }
        if ("file".equalsIgnoreCase(scheme)) {
            String path = uri.getPath();
            if (path == null || path.isEmpty()) {
                throw new IOException("empty file URI");
            }
            File file = new File(path).getCanonicalFile();
            String dataDir = context.getApplicationInfo() != null
                    ? context.getApplicationInfo().dataDir : null;
            if (dataDir != null && !dataDir.isEmpty()) {
                String canonicalData = new File(dataDir).getCanonicalPath();
                String targetPath = file.getCanonicalPath();
                if (targetPath.equals(canonicalData)
                        || targetPath.startsWith(canonicalData + File.separator)) {
                    throw new IOException("refusing private app file URI");
                }
            }
            if (!file.isFile() || !file.canRead()) {
                throw new IOException("image file not readable");
            }
            return new FileInputStream(file);
        }
        throw new IOException("unsupported URI scheme");
    }

    private static byte[] readBounded(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
        byte[] buf = new byte[16 * 1024];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new IOException("image exceeds 12 MiB limit");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static String resolveFileName(Context context, Uri uri, String mime) {
        String raw = null;
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            Cursor cursor = null;
            try {
                ContentResolver cr = context.getContentResolver();
                cursor = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) {
                        raw = cursor.getString(idx);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (cursor != null) {
                    try {
                        cursor.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        if (raw == null || raw.trim().isEmpty()) {
            raw = uri.getLastPathSegment();
        }
        String safe = SharePayloadExtractor.sanitizeFileName(raw);
        String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
        if (ext == null || ext.isEmpty()) {
            ext = "png";
        }
        if (safe.isEmpty()) {
            return "pasted_" + System.currentTimeMillis() + "." + ext;
        }
        if (!safe.contains(".")) {
            return safe + "." + ext;
        }
        return safe;
    }

    public static void deliverPastedImageToWebView(final WebView webView, final ImagePayload payload) {
        if (webView == null || payload == null) {
            return;
        }
        webView.post(new Runnable() {
            @Override
            public void run() {
                String js = "window.__agDeliverPastedImage && window.__agDeliverPastedImage("
                        + JSONObject.quote(payload.base64) + ","
                        + JSONObject.quote(payload.mime) + ","
                        + JSONObject.quote(payload.name) + ");";
                webView.evaluateJavascript(js, null);
            }
        });
    }

    private static String errorJson(String message) {
        try {
            JSONObject obj = new JSONObject();
            obj.put("ok", false);
            obj.put("error", message != null ? message : "unknown");
            return obj.toString();
        } catch (Exception ignored) {
            return "{\"ok\":false,\"error\":\"json_error\"}";
        }
    }
}
