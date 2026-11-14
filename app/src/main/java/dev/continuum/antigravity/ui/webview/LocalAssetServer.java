package dev.continuum.antigravity.ui.webview;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceResponse;

import dev.continuum.antigravity.util.MimeTypes;
import dev.continuum.antigravity.util.PathSafety;
import dev.continuum.antigravity.util.TrustedCoreOrigin;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Serves our extracted web assets from filesDir/web for loopback requests,
 * bypassing the core for static files. RPC/streaming endpoints are left alone.
 */
public final class LocalAssetServer {

    private static final String TAG = "WebViewHost";

    public WebResourceResponse intercept(Context context, Uri uri, String method, int enginePort) {
        return intercept(context, uri, method, enginePort, null);
    }

    public WebResourceResponse intercept(Context context, Uri uri, String method, int enginePort,
                                         Map<String, String> requestHeaders) {
        if (uri == null || !"GET".equalsIgnoreCase(method)) {
            return null;
        }
        if (!TrustedCoreOrigin.matches(uri, enginePort)) {
            return null;
        }
        String path = uri.getPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return null;
        }
        // Do not intercept RPC or streaming API endpoints
        if (path.startsWith("/exa.") || path.startsWith("/jetski.") || path.contains("Service")
                || path.contains("connect-websocket")) {
            return null;
        }
        String relPath = path.startsWith("/") ? path.substring(1) : path;
        File webDir = new File(context.getFilesDir(), "web");
        File target = new File(webDir, relPath);
        try {
            String canonicalWeb = webDir.getCanonicalPath();
            String canonicalTarget = target.getCanonicalPath();
            if (!canonicalTarget.startsWith(canonicalWeb + File.separator)
                    && !canonicalTarget.equals(canonicalWeb)) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        if (target.isFile() && target.canRead()) {
            try {
                String mime = MimeTypes.getMimeType(target.getName());
                String encoding = MimeTypes.isTextMime(mime) ? "UTF-8" : null;
                long length = target.length();
                String etag = AssetCachePolicy.etagFor(target.lastModified(), length);
                Map<String, String> headers = new HashMap<>();
                headers.put("Access-Control-Allow-Origin", "*");
                // Never immutable: these filenames are stable across releases, so
                // caching them hard is exactly how a newer app ends up rendering
                // an older bundle. Revalidation is a local stat and answers 304.
                headers.put("Cache-Control", AssetCachePolicy.CACHE_CONTROL);
                headers.put("ETag", etag);
                if (AssetCachePolicy.matchesEtag(
                        AssetCachePolicy.header(requestHeaders, "If-None-Match"), etag)) {
                    return new WebResourceResponse(mime, encoding, 304, "Not Modified", headers,
                            new java.io.ByteArrayInputStream(new byte[0]));
                }
                headers.put("Content-Length", String.valueOf(length));
                InputStream bis = new BufferedInputStream(new FileInputStream(target), 64 * 1024);
                return new WebResourceResponse(mime, encoding, 200, "OK", headers, bis);
            } catch (Exception e) {
                Log.w(TAG, "intercept failed for " + path, e);
            }
        }
        return null;
    }
}
