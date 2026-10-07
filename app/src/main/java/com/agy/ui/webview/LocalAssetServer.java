package com.agy.ui.webview;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceResponse;

import com.agy.util.MimeTypes;
import com.agy.util.PathSafety;

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
        if (uri == null || !"GET".equalsIgnoreCase(method)) {
            return null;
        }
        String host = uri.getHost();
        if (!com.agy.util.LoopbackHost.isLoopback(host)) {
            return null;
        }
        if (uri.getPort() != enginePort) {
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
                Map<String, String> headers = new HashMap<>();
                headers.put("Access-Control-Allow-Origin", "*");
                headers.put("Cache-Control", "public, max-age=31536000, immutable");
                headers.put("Content-Length", String.valueOf(target.length()));
                InputStream bis = new BufferedInputStream(new FileInputStream(target), 64 * 1024);
                return new WebResourceResponse(mime, encoding, 200, "OK", headers, bis);
            } catch (Exception e) {
                Log.w(TAG, "intercept failed for " + path, e);
            }
        }
        return null;
    }
}
