package com.agy.ui.webview;

import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import com.agy.BuildConfig;
import com.agy.gateway.SystemGatewayServer;
import com.agy.util.MimeTypes;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loopback-only HTTP proxy for embedded plugin sidecars (e.g. Swarm UI on
 * http://localhost:<port>). Chromium WebView's schemeful cross-site cookie
 * rules withhold cookies set by http://localhost iframes embedded inside the
 * https://127.0.0.1 core UI, causing sidecar gateways to return 401 for CSS,
 * JS, and API subresources. This proxy captures Set-Cookie across 200 and 3xx
 * responses per loopback port and replays them on subsequent GET/HEAD requests,
 * preserving streaming SSE (text/event-stream) responses.
 */
public final class LoopbackSidecarProxy {

    private static final String TAG = "LoopbackSidecarProxy";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 15000;
    private static final int MAX_REDIRECT_HOPS = 5;
    private static final int MAX_TRACKED_PORTS = 64;
    private static final int MAX_COOKIES_PER_PORT = 32;

    private final ConcurrentHashMap<Integer, ConcurrentHashMap<String, String>> cookiesByPort =
            new ConcurrentHashMap<>();

    public WebResourceResponse intercept(WebResourceRequest request, int coreEnginePort) {
        if (request == null || request.getUrl() == null) {
            return null;
        }
        String method = request.getMethod();
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
            return null;
        }
        Uri uri = request.getUrl();
        if (!isEligibleSidecarUri(uri, coreEnginePort)) {
            return null;
        }

        final int port = uri.getPort();
        final String originalHost = uri.getHost();
        final Map<String, String> reqHeaders = request.getRequestHeaders();
        final boolean requestExpectsSse = acceptsEventStream(reqHeaders);

        Uri currentUri = uri;
        for (int hop = 0; hop <= MAX_REDIRECT_HOPS; hop++) {
            HttpURLConnection conn = null;
            boolean handedOffStream = false;
            try {
                URL targetUrl = toLoopbackUrl(currentUri, originalHost, port);
                conn = (HttpURLConnection) targetUrl.openConnection();
                conn.setRequestMethod(method.toUpperCase(Locale.US));
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(requestExpectsSse ? 0 : DEFAULT_READ_TIMEOUT_MS);
                conn.setUseCaches(false);

                copyRequestHeaders(conn, reqHeaders, originalHost, port);
                attachCookies(conn, reqHeaders, port);

                int status = conn.getResponseCode();
                captureSetCookies(conn, port);

                if (isRedirectStatus(status)) {
                    String location = conn.getHeaderField("Location");
                    Uri nextUri = resolveSamePortRedirect(currentUri, location, port);
                    if (nextUri != null && hop < MAX_REDIRECT_HOPS) {
                        drainAndDisconnect(conn);
                        currentUri = nextUri;
                        continue;
                    }
                }

                String rawContentType = conn.getContentType();
                String mimeType = extractMimeType(rawContentType, currentUri.getPath());
                String encoding = extractCharset(rawContentType, mimeType);
                boolean isSse = "text/event-stream".equalsIgnoreCase(mimeType);
                if (isSse) {
                    try {
                        conn.setReadTimeout(0);
                    } catch (Exception ignored) {
                    }
                }

                Map<String, String> respHeaders = extractResponseHeaders(conn);
                String reason = safeReasonPhrase(conn.getResponseMessage(), status);

                InputStream bodyStream;
                if ("HEAD".equalsIgnoreCase(method) || status == 204 || status == 304) {
                    drainAndDisconnect(conn);
                    bodyStream = new ByteArrayInputStream(new byte[0]);
                } else {
                    InputStream rawStream = (status >= 400) ? conn.getErrorStream() : conn.getInputStream();
                    if (rawStream == null) {
                        drainAndDisconnect(conn);
                        bodyStream = new ByteArrayInputStream(new byte[0]);
                    } else {
                        bodyStream = new DisconnectingInputStream(rawStream, conn);
                        handedOffStream = true;
                    }
                }

                return new WebResourceResponse(mimeType, encoding, status, reason, respHeaders, bodyStream);
            } catch (IOException e) {
                if (conn != null && !handedOffStream) {
                    conn.disconnect();
                }
                Log.w(TAG, "loopback proxy failed for port=" + port + " path=" + currentUri.getPath()
                        + ": " + e.getMessage());
                return null;
            } catch (Throwable t) {
                if (conn != null && !handedOffStream) {
                    conn.disconnect();
                }
                Log.w(TAG, "unexpected loopback proxy error for port=" + port + ": " + t.getMessage());
                return null;
            }
        }
        return null;
    }

    private static boolean isEligibleSidecarUri(Uri uri, int coreEnginePort) {
        if (uri == null || uri.getUserInfo() != null) {
            return false;
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())) {
            return false;
        }
        String host = uri.getHost();
        if (!"localhost".equalsIgnoreCase(host) && !"127.0.0.1".equals(host)) {
            return false;
        }
        int port = uri.getPort();
        if (port <= 0 || port > 65535) {
            return false;
        }
        if (port == coreEnginePort) {
            return false;
        }
        if (isExcludedGatewayPort(port)) {
            return false;
        }
        return true;
    }

    private static boolean isExcludedGatewayPort(int port) {
        if (port >= 45160 && port <= 45163) {
            return true;
        }
        if (port == BuildConfig.GATEWAY_PORT) {
            return true;
        }
        int activeGw = SystemGatewayServer.getActivePort();
        return activeGw > 0 && port == activeGw;
    }

    private static URL toLoopbackUrl(Uri uri, String host, int port) throws IOException {
        String safeHost = "127.0.0.1".equals(host) ? "127.0.0.1" : "localhost";
        String encodedPath = uri.getEncodedPath();
        if (encodedPath == null || encodedPath.isEmpty()) {
            encodedPath = "/";
        }
        String encodedQuery = uri.getEncodedQuery();
        StringBuilder sb = new StringBuilder();
        sb.append("http://").append(safeHost).append(':').append(port).append(encodedPath);
        if (encodedQuery != null) {
            sb.append('?').append(encodedQuery);
        }
        return new URL(sb.toString());
    }

    private static void copyRequestHeaders(HttpURLConnection conn, Map<String, String> headers,
                                           String originalHost, int port) {
        if (originalHost != null && !originalHost.isEmpty()) {
            conn.setRequestProperty("Host", originalHost + ":" + port);
        }
        if (headers == null || headers.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey();
            String val = entry.getValue();
            if (key == null || val == null) {
                continue;
            }
            // Skip hop-by-hop, Cookie (merged separately), Host (set above), and
            // Accept-Encoding (let HttpURLConnection decode transparently).
            if ("Cookie".equalsIgnoreCase(key)
                    || "Host".equalsIgnoreCase(key)
                    || "Connection".equalsIgnoreCase(key)
                    || "Accept-Encoding".equalsIgnoreCase(key)
                    || "Content-Length".equalsIgnoreCase(key)) {
                continue;
            }
            conn.setRequestProperty(key, val);
        }
    }

    private void attachCookies(HttpURLConnection conn, Map<String, String> reqHeaders, int port) {
        Map<String, String> merged = new HashMap<>();
        if (reqHeaders != null) {
            for (Map.Entry<String, String> entry : reqHeaders.entrySet()) {
                if ("Cookie".equalsIgnoreCase(entry.getKey()) && entry.getValue() != null) {
                    parseCookieHeaderInto(entry.getValue(), merged);
                }
            }
        }
        ConcurrentHashMap<String, String> stored = cookiesByPort.get(port);
        if (stored != null && !stored.isEmpty()) {
            merged.putAll(stored);
        }
        if (merged.isEmpty()) {
            return;
        }
        StringBuilder cookieHeader = new StringBuilder();
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            if (cookieHeader.length() > 0) {
                cookieHeader.append("; ");
            }
            cookieHeader.append(entry.getKey()).append('=').append(entry.getValue());
        }
        conn.setRequestProperty("Cookie", cookieHeader.toString());
    }

    private void captureSetCookies(HttpURLConnection conn, int port) {
        Map<String, List<String>> headerFields = conn.getHeaderFields();
        if (headerFields == null || headerFields.isEmpty()) {
            return;
        }
        for (Map.Entry<String, List<String>> entry : headerFields.entrySet()) {
            if (!"Set-Cookie".equalsIgnoreCase(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            for (String rawCookie : entry.getValue()) {
                storeSetCookie(port, rawCookie);
            }
        }
    }

    private void storeSetCookie(int port, String rawSetCookie) {
        if (rawSetCookie == null || rawSetCookie.trim().isEmpty()) {
            return;
        }
        String[] parts = rawSetCookie.split(";");
        if (parts.length == 0) {
            return;
        }
        String pair = parts[0].trim();
        int eq = pair.indexOf('=');
        if (eq <= 0) {
            return;
        }
        String name = pair.substring(0, eq).trim();
        String value = pair.substring(eq + 1).trim();
        if (name.isEmpty()) {
            return;
        }

        boolean expired = false;
        for (int i = 1; i < parts.length; i++) {
            String attr = parts[i].trim().toLowerCase(Locale.US);
            if (attr.startsWith("max-age=")) {
                try {
                    long maxAge = Long.parseLong(attr.substring("max-age=".length()).trim());
                    if (maxAge <= 0L) {
                        expired = true;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        ConcurrentHashMap<String, String> jar = cookiesByPort.get(port);
        if (jar == null) {
            if (expired) {
                return;
            }
            if (cookiesByPort.size() >= MAX_TRACKED_PORTS) {
                cookiesByPort.clear();
            }
            ConcurrentHashMap<String, String> fresh = new ConcurrentHashMap<>();
            ConcurrentHashMap<String, String> existing = cookiesByPort.putIfAbsent(port, fresh);
            jar = existing != null ? existing : fresh;
        }

        if (expired) {
            jar.remove(name);
            return;
        }
        if (jar.size() >= MAX_COOKIES_PER_PORT && !jar.containsKey(name)) {
            jar.clear();
        }
        jar.put(name, value);
    }

    private static void parseCookieHeaderInto(String rawCookieHeader, Map<String, String> target) {
        String[] parts = rawCookieHeader.split(";");
        for (String part : parts) {
            String trimmed = part.trim();
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                String k = trimmed.substring(0, eq).trim();
                String v = trimmed.substring(eq + 1).trim();
                if (!k.isEmpty()) {
                    target.put(k, v);
                }
            }
        }
    }

    private static boolean isRedirectStatus(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static Uri resolveSamePortRedirect(Uri currentUri, String location, int port) {
        if (location == null || location.trim().isEmpty()) {
            return null;
        }
        String loc = location.trim();
        try {
            if (loc.startsWith("/")) {
                String host = currentUri.getHost() != null ? currentUri.getHost() : "localhost";
                return Uri.parse("http://" + host + ":" + port + loc);
            }
            Uri parsed = Uri.parse(loc);
            if ("http".equalsIgnoreCase(parsed.getScheme())
                    && parsed.getUserInfo() == null
                    && parsed.getPort() == port
                    && ("localhost".equalsIgnoreCase(parsed.getHost())
                        || "127.0.0.1".equals(parsed.getHost()))) {
                return parsed;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean acceptsEventStream(Map<String, String> reqHeaders) {
        if (reqHeaders == null) {
            return false;
        }
        for (Map.Entry<String, String> entry : reqHeaders.entrySet()) {
            if ("Accept".equalsIgnoreCase(entry.getKey()) && entry.getValue() != null
                    && entry.getValue().toLowerCase(Locale.US).contains("text/event-stream")) {
                return true;
            }
        }
        return false;
    }

    private static String extractMimeType(String rawContentType, String path) {
        if (rawContentType != null && !rawContentType.trim().isEmpty()) {
            int semi = rawContentType.indexOf(';');
            String base = (semi >= 0 ? rawContentType.substring(0, semi) : rawContentType).trim();
            if (!base.isEmpty()) {
                return base;
            }
        }
        if (path != null && !path.isEmpty() && !"/".equals(path)) {
            return MimeTypes.getMimeType(path);
        }
        return "text/html";
    }

    private static String extractCharset(String rawContentType, String mimeType) {
        if (rawContentType != null) {
            String[] parts = rawContentType.split(";");
            for (int i = 1; i < parts.length; i++) {
                String part = parts[i].trim();
                if (part.toLowerCase(Locale.US).startsWith("charset=")) {
                    String cs = part.substring("charset=".length()).trim();
                    if (cs.startsWith("\"") && cs.endsWith("\"") && cs.length() >= 2) {
                        cs = cs.substring(1, cs.length() - 1);
                    }
                    if (!cs.isEmpty()) {
                        return cs;
                    }
                }
            }
        }
        return MimeTypes.isTextMime(mimeType) || "text/event-stream".equalsIgnoreCase(mimeType)
                ? "UTF-8" : null;
    }

    private static Map<String, String> extractResponseHeaders(HttpURLConnection conn) {
        Map<String, String> out = new HashMap<>();
        Map<String, List<String>> fields = conn.getHeaderFields();
        if (fields == null) {
            return out;
        }
        for (Map.Entry<String, List<String>> entry : fields.entrySet()) {
            String key = entry.getKey();
            List<String> values = entry.getValue();
            if (key == null || values == null || values.isEmpty()) {
                continue;
            }
            if ("Transfer-Encoding".equalsIgnoreCase(key)
                    || "Content-Encoding".equalsIgnoreCase(key)
                    || "Connection".equalsIgnoreCase(key)
                    || "Keep-Alive".equalsIgnoreCase(key)) {
                continue;
            }
            if (values.size() == 1) {
                out.put(key, values.get(0));
            } else {
                StringBuilder joined = new StringBuilder();
                for (int i = 0; i < values.size(); i++) {
                    if (i > 0) {
                        joined.append(", ");
                    }
                    joined.append(values.get(i));
                }
                out.put(key, joined.toString());
            }
        }
        return out;
    }

    private static String safeReasonPhrase(String upstreamMessage, int status) {
        if (upstreamMessage != null && !upstreamMessage.trim().isEmpty()) {
            return upstreamMessage.trim();
        }
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 500: return "Internal Server Error";
            default: return "HTTP " + status;
        }
    }

    private static void drainAndDisconnect(HttpURLConnection conn) {
        if (conn == null) {
            return;
        }
        try {
            InputStream in = conn.getInputStream();
            if (in != null) {
                in.close();
            }
        } catch (Exception ignored) {
            try {
                InputStream err = conn.getErrorStream();
                if (err != null) {
                    err.close();
                }
            } catch (Exception ignored2) {
            }
        } finally {
            conn.disconnect();
        }
    }

    private static final class DisconnectingInputStream extends FilterInputStream {
        private final HttpURLConnection conn;
        private boolean closed = false;

        DisconnectingInputStream(InputStream in, HttpURLConnection conn) {
            super(in);
            this.conn = conn;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                super.close();
            } finally {
                conn.disconnect();
            }
        }
    }
}
