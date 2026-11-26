package com.agy.ui.webview;

import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import com.agy.util.MimeTypes;
import com.agy.util.SensitiveDataRedactor;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Captures sidecar auth tokens from initial loopback iframe navigations (?token=...)
 * and attaches X-Sidecar-Token on subsequent GET/HEAD subresource requests to that port.
 *
 * This bypasses Android WebView cross-site cookie blocking when the main app is hosted
 * on https://127.0.0.1:<enginePort> (via loadDataWithBaseURL) and embeds sidecars on
 * http://localhost:<gatewayPort>.
 */
public final class LoopbackSidecarProxy {

    private static final String TAG = "WebViewHost";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 15000;

    private static final Set<String> SKIPPED_REQUEST_HEADERS = new HashSet<>(Arrays.asList(
            "host",
            "connection",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade",
            "content-length",
            "accept-encoding",
            "if-none-match",
            "if-modified-since",
            "if-match",
            "if-unmodified-since",
            "if-range",
            "cookie"
    ));

    private static final Set<String> SKIPPED_RESPONSE_HEADERS = new HashSet<>(Arrays.asList(
            "transfer-encoding",
            "connection",
            "keep-alive"
    ));

    private final int enginePort;
    private final int gatewayPort;
    private final ConcurrentHashMap<Integer, String> portTokens = new ConcurrentHashMap<>();

    public LoopbackSidecarProxy(int enginePort, int gatewayPort) {
        this.enginePort = enginePort;
        this.gatewayPort = gatewayPort;
    }

    public WebResourceResponse intercept(WebResourceRequest request) {
        if (request == null) {
            return null;
        }
        return intercept(request.getUrl(), request.getMethod(), request.getRequestHeaders());
    }

    public WebResourceResponse intercept(Uri uri, String method, Map<String, String> requestHeaders) {
        if (uri == null || method == null) {
            return null;
        }
        String upperMethod = method.toUpperCase(Locale.US);
        if (!"GET".equals(upperMethod) && !"HEAD".equals(upperMethod)) {
            return null;
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())) {
            return null;
        }
        if (uri.getUserInfo() != null) {
            return null;
        }
        String host = uri.getHost();
        if (!"localhost".equalsIgnoreCase(host) && !"127.0.0.1".equals(host)) {
            return null;
        }
        int port = uri.getPort();
        if (port <= 0 || port == enginePort || (gatewayPort > 0 && port == gatewayPort)) {
            return null;
        }

        if ("GET".equals(upperMethod)) {
            String queryToken = extractTokenParam(uri);
            if (queryToken != null && !queryToken.isEmpty()) {
                portTokens.put(port, queryToken);
                return null;
            }
        }

        String token = portTokens.get(port);
        if (token == null || token.isEmpty()) {
            return null;
        }

        return forwardRequest(uri, host, port, upperMethod, requestHeaders, token);
    }

    private WebResourceResponse forwardRequest(Uri uri, String origHost, int port,
                                               String method, Map<String, String> requestHeaders,
                                               String token) {
        HttpURLConnection conn = null;
        try {
            String encodedPath = uri.getEncodedPath();
            if (encodedPath == null || encodedPath.isEmpty()) {
                encodedPath = "/";
            }
            String encodedQuery = uri.getEncodedQuery();
            StringBuilder targetUrl = new StringBuilder("http://127.0.0.1:")
                    .append(port)
                    .append(encodedPath);
            if (encodedQuery != null && !encodedQuery.isEmpty()) {
                targetUrl.append('?').append(encodedQuery);
            }

            conn = (HttpURLConnection) new URL(targetUrl.toString()).openConnection();
            conn.setRequestMethod(method);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);
            conn.setUseCaches(false);

            if ("localhost".equalsIgnoreCase(origHost)) {
                conn.setRequestProperty("Host", "localhost:" + port);
            }

            boolean hasTokenHeader = false;
            if (requestHeaders != null) {
                for (Map.Entry<String, String> entry : requestHeaders.entrySet()) {
                    String key = entry.getKey();
                    String value = entry.getValue();
                    if (key == null || value == null) {
                        continue;
                    }
                    String lowerKey = key.toLowerCase(Locale.US);
                    if (SKIPPED_REQUEST_HEADERS.contains(lowerKey)) {
                        continue;
                    }
                    if ("x-sidecar-token".equals(lowerKey)) {
                        hasTokenHeader = true;
                    }
                    conn.setRequestProperty(key, value);
                }
            }
            if (!hasTokenHeader) {
                conn.setRequestProperty("X-Sidecar-Token", token);
            }

            int statusCode = conn.getResponseCode();
            if (statusCode < 100) {
                conn.disconnect();
                return null;
            }

            String reasonPhrase = conn.getResponseMessage();
            if (reasonPhrase == null || reasonPhrase.trim().isEmpty()) {
                reasonPhrase = "OK";
            } else {
                reasonPhrase = reasonPhrase.trim();
            }

            ContentTypeInfo contentType = parseContentType(conn.getContentType(), encodedPath);
            Map<String, String> responseHeaders = collectResponseHeaders(conn.getHeaderFields());

            if ("HEAD".equals(method)) {
                conn.disconnect();
                return new WebResourceResponse(
                        contentType.mimeType,
                        contentType.encoding,
                        statusCode,
                        reasonPhrase,
                        responseHeaders,
                        new ByteArrayInputStream(new byte[0])
                );
            }

            InputStream rawStream = statusCode >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (rawStream == null) {
                conn.disconnect();
                return new WebResourceResponse(
                        contentType.mimeType,
                        contentType.encoding,
                        statusCode,
                        reasonPhrase,
                        responseHeaders,
                        new ByteArrayInputStream(new byte[0])
                );
            }

            InputStream bodyStream = new DisconnectingInputStream(
                    new BufferedInputStream(rawStream, 16 * 1024), conn);
            return new WebResourceResponse(
                    contentType.mimeType,
                    contentType.encoding,
                    statusCode,
                    reasonPhrase,
                    responseHeaders,
                    bodyStream
            );
        } catch (Exception e) {
            Log.w(TAG, "sidecar proxy failed for " + SensitiveDataRedactor.safeUrl(uri.toString()), e);
            if (conn != null) {
                conn.disconnect();
            }
            return null;
        }
    }

    private static String extractTokenParam(Uri uri) {
        try {
            return uri.getQueryParameter("token");
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Map<String, String> collectResponseHeaders(Map<String, List<String>> rawHeaders) {
        Map<String, String> result = new HashMap<>();
        if (rawHeaders == null) {
            return result;
        }
        for (Map.Entry<String, List<String>> entry : rawHeaders.entrySet()) {
            String key = entry.getKey();
            List<String> values = entry.getValue();
            if (key == null || values == null || values.isEmpty()) {
                continue;
            }
            if (SKIPPED_RESPONSE_HEADERS.contains(key.toLowerCase(Locale.US))) {
                continue;
            }
            StringBuilder joined = new StringBuilder();
            for (String v : values) {
                if (v == null) {
                    continue;
                }
                if (joined.length() > 0) {
                    joined.append(", ");
                }
                joined.append(v);
            }
            if (joined.length() > 0) {
                result.put(key, joined.toString());
            }
        }
        return result;
    }

    private static ContentTypeInfo parseContentType(String header, String path) {
        String mimeType = null;
        String charset = null;

        if (header != null && !header.trim().isEmpty()) {
            String[] parts = header.split(";");
            if (parts.length > 0) {
                String candidate = parts[0].trim().toLowerCase(Locale.US);
                if (!candidate.isEmpty()) {
                    mimeType = candidate;
                }
            }
            for (int i = 1; i < parts.length; i++) {
                String param = parts[i].trim();
                if (param.toLowerCase(Locale.US).startsWith("charset=")) {
                    String value = param.substring("charset=".length()).trim();
                    if ((value.startsWith("\"") && value.endsWith("\""))
                            || (value.startsWith("'") && value.endsWith("'"))) {
                        if (value.length() >= 2) {
                            value = value.substring(1, value.length() - 1).trim();
                        }
                    }
                    if (!value.isEmpty()) {
                        charset = value;
                    }
                }
            }
        }

        if (mimeType == null) {
            mimeType = MimeTypes.getMimeType(path);
        }

        String encoding = null;
        if (MimeTypes.isTextMime(mimeType)) {
            encoding = (charset != null && !charset.isEmpty()) ? charset : "UTF-8";
        }
        return new ContentTypeInfo(mimeType, encoding);
    }

    private static final class ContentTypeInfo {
        final String mimeType;
        final String encoding;

        ContentTypeInfo(String mimeType, String encoding) {
            this.mimeType = mimeType;
            this.encoding = encoding;
        }
    }

    private static final class DisconnectingInputStream extends FilterInputStream {
        private final HttpURLConnection conn;
        private final AtomicBoolean closed = new AtomicBoolean(false);

        DisconnectingInputStream(InputStream in, HttpURLConnection conn) {
            super(in);
            this.conn = conn;
        }

        @Override
        public void close() throws IOException {
            if (closed.compareAndSet(false, true)) {
                try {
                    super.close();
                } finally {
                    conn.disconnect();
                }
            }
        }
    }
}
