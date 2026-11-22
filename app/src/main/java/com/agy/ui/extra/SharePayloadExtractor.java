package com.agy.ui.extra;

import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.MimeTypeMap;

import com.agy.util.PathSafety;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Extracts text, subject, and shared content:// files from ACTION_SEND and
 * ACTION_SEND_MULTIPLE intents, copying content URIs into cacheDir/shared/
 * with sanitized filenames and serializing to JSON for WebView delivery.
 */
public final class SharePayloadExtractor {

    private static final String TAG = "SharePayloadExtractor";
    private static final String SHARED_SUBDIR = "shared";
    private static final int MAX_FILES = 20;
    private static final long MAX_FILE_BYTES = 50L * 1024L * 1024L; // 50 MiB per file
    private static final long STALE_AGE_MS = 24L * 60L * 60L * 1000L; // 24 hours

    private SharePayloadExtractor() {
    }

    public static boolean isShareIntent(Intent intent) {
        if (intent == null) {
            return false;
        }
        String action = intent.getAction();
        return Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action);
    }

    public static boolean hasStreamUris(Intent intent) {
        return !collectStreamUris(intent).isEmpty();
    }

    /**
     * Extracts shared text/subject/files into a JSON string:
     * {@code {"text": "...", "subject": "...", "files": [{"name": "...", "path": "...", "mime": "..."}]}}.
     * Returns null if nothing usable was present in the intent.
     */
    public static String extractToJson(Context context, Intent intent) {
        if (context == null || !isShareIntent(intent)) {
            return null;
        }
        String text = extractText(intent);
        String subject = extractSubject(intent);
        List<Uri> uris = collectStreamUris(intent);

        JSONArray filesJson = new JSONArray();
        if (!uris.isEmpty()) {
            File sharedDir = new File(context.getCacheDir(), SHARED_SUBDIR);
            if (sharedDir.exists() || sharedDir.mkdirs()) {
                pruneStaleFiles(sharedDir);
                int copied = 0;
                for (Uri uri : uris) {
                    if (copied >= MAX_FILES) {
                        break;
                    }
                    JSONObject entry = copyContentUri(context, sharedDir, uri, intent.getType());
                    if (entry != null) {
                        filesJson.put(entry);
                        copied++;
                    }
                }
            } else {
                Log.w(TAG, "cannot create shared cache directory");
            }
        }

        if (text.isEmpty() && subject.isEmpty() && filesJson.length() == 0) {
            return null;
        }

        try {
            JSONObject payload = new JSONObject();
            payload.put("text", text);
            payload.put("subject", subject);
            payload.put("files", filesJson);
            return payload.toString();
        } catch (Exception e) {
            Log.e(TAG, "failed to build share payload JSON", e);
            return null;
        }
    }

    private static String extractText(Intent intent) {
        CharSequence cs = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (cs != null && cs.length() > 0) {
            return cs.toString();
        }
        String str = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (str != null && !str.isEmpty()) {
            return str;
        }
        String html = intent.getStringExtra(Intent.EXTRA_HTML_TEXT);
        return html != null ? html : "";
    }

    private static String extractSubject(Intent intent) {
        CharSequence cs = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
        if (cs != null && cs.length() > 0) {
            return cs.toString();
        }
        String str = intent.getStringExtra(Intent.EXTRA_SUBJECT);
        return str != null ? str : "";
    }

    @SuppressWarnings("deprecation")
    private static List<Uri> collectStreamUris(Intent intent) {
        Set<Uri> unique = new LinkedHashSet<>();
        if (intent == null) {
            return new ArrayList<>();
        }
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            Uri stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (stream != null) {
                unique.add(stream);
            }
        } else if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> streams = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (streams != null) {
                for (Uri u : streams) {
                    if (u != null) {
                        unique.add(u);
                    }
                }
            }
        }

        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                ClipData.Item item = clip.getItemAt(i);
                if (item != null && item.getUri() != null) {
                    unique.add(item.getUri());
                }
            }
        }
        return new ArrayList<>(unique);
    }

    private static JSONObject copyContentUri(Context context, File sharedDir, Uri uri, String fallbackMime) {
        if (uri == null) {
            return null;
        }
        // Only accept content:// URIs from external share senders to prevent
        // arbitrary file:// local-file exfiltration tricks.
        if (!"content".equalsIgnoreCase(uri.getScheme())) {
            Log.w(TAG, "ignoring non-content shared URI scheme=" + uri.getScheme());
            return null;
        }

        ContentResolver resolver = context.getContentResolver();
        String mime = null;
        try {
            mime = resolver.getType(uri);
        } catch (Exception ignored) {
        }
        if (mime == null || mime.trim().isEmpty()) {
            mime = (fallbackMime != null && !fallbackMime.trim().isEmpty() && !"*/*".equals(fallbackMime))
                    ? fallbackMime.trim()
                    : "application/octet-stream";
        }

        String rawName = queryDisplayName(resolver, uri);
        if (rawName == null || rawName.trim().isEmpty()) {
            rawName = uri.getLastPathSegment();
        }
        String safeName = sanitizeFileName(rawName);
        if (safeName.isEmpty()) {
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime);
            safeName = "shared_" + System.currentTimeMillis()
                    + (ext != null && !ext.isEmpty() ? "." + sanitizeFileName(ext) : "");
        }

        File target = null;
        try {
            File canonicalRoot = sharedDir.getCanonicalFile();
            target = getSafeUniqueFile(canonicalRoot, safeName);

            long total = 0L;
            try (InputStream in = resolver.openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(target)) {
                if (in == null) {
                    safeDelete(target);
                    return null;
                }
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_FILE_BYTES) {
                        throw new IOException("shared file exceeds 50 MiB limit");
                    }
                    out.write(buf, 0, n);
                }
                out.flush();
            }

            JSONObject obj = new JSONObject();
            obj.put("name", target.getName());
            obj.put("path", target.getAbsolutePath());
            obj.put("mime", mime);
            return obj;
        } catch (Exception e) {
            Log.w(TAG, "failed to copy shared content URI: " + e.getMessage());
            safeDelete(target);
            return null;
        }
    }

    private static String queryDisplayName(ContentResolver resolver, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    return cursor.getString(idx);
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
        return null;
    }

    public static String sanitizeFileName(String raw) {
        if (raw == null) {
            return "";
        }
        String name = raw.trim().replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        StringBuilder sb = new StringBuilder(Math.min(name.length(), 120));
        for (int i = 0; i < name.length() && sb.length() < 120; i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f || c == '/' || c == '\\' || c == ':' || c == '*'
                    || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String cleaned = sb.toString().trim();
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.isEmpty() || ".".equals(cleaned) || "..".equals(cleaned)) {
            return "";
        }
        return cleaned;
    }

    private static File getSafeUniqueFile(File canonicalRoot, String safeName) throws IOException {
        File candidate = new File(canonicalRoot, safeName).getCanonicalFile();
        if (candidate.equals(canonicalRoot) || !PathSafety.isInside(canonicalRoot, candidate)) {
            throw new IOException("shared file path escaped cacheDir/shared");
        }
        if (!candidate.exists()) {
            return candidate;
        }
        String name = candidate.getName();
        String base = name;
        String ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            base = name.substring(0, dot);
            ext = name.substring(dot);
        }
        int counter = 1;
        while (candidate.exists()) {
            candidate = new File(canonicalRoot, base + "_" + counter + ext).getCanonicalFile();
            if (candidate.equals(canonicalRoot) || !PathSafety.isInside(canonicalRoot, candidate)) {
                throw new IOException("shared file path escaped cacheDir/shared");
            }
            counter++;
        }
        return candidate;
    }

    private static void pruneStaleFiles(File sharedDir) {
        try {
            File[] children = sharedDir.listFiles();
            if (children == null) {
                return;
            }
            long cutoff = System.currentTimeMillis() - STALE_AGE_MS;
            for (File f : children) {
                if (f != null && f.isFile() && f.lastModified() > 0 && f.lastModified() < cutoff) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
        } catch (Exception ignored) {
        }
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
