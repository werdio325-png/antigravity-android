#!/usr/bin/env python3
"""
test_sidecar_proxy.py - End-to-end verification of LoopbackSidecarProxy against
a live HTTP server simulating gateway.go auth + strict MIME behavior.
"""
import http.server
import os
import shutil
import socketserver
import subprocess
import tempfile
import threading
import unittest
import urllib.parse


TESTS_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.abspath(os.path.join(TESTS_DIR, "..", "..", ".."))
JAVA_SRC_DIR = os.path.join(PROJECT_ROOT, "app", "src", "main", "java")


class ThreadingHTTPServer(socketserver.ThreadingMixIn, http.server.HTTPServer):
    daemon_threads = True


class MockGatewayState:
    def __init__(self, expected_token):
        self.expected_token = expected_token
        self.seen_requests = []
        self.lock = threading.Lock()


def make_gateway_handler(state):
    class MockGatewayHandler(http.server.BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, format, *args):
            pass

        def _record(self, method, path):
            with state.lock:
                state.seen_requests.append((method, path, dict(self.headers)))

        def _send_bytes(self, status, reason, content_type, body, extra_headers=None):
            self.send_response(status, reason)
            if content_type is not None:
                self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            if extra_headers:
                for k, v in extra_headers.items():
                    self.send_header(k, v)
            self.end_headers()
            if self.command != "HEAD" and body:
                self.wfile.write(body)

        def _handle_get_or_head(self):
            parsed = urllib.parse.urlparse(self.path)
            qs = urllib.parse.parse_qs(parsed.query, keep_blank_values=True)
            self._record(self.command, self.path)

            header_token = self.headers.get("X-Sidecar-Token")
            query_token = qs.get("token", [None])[0]

            if parsed.path == "/" and query_token == state.expected_token:
                body = b"<!doctype html><html><body>swarm</body></html>"
                self._send_bytes(
                    200,
                    "OK",
                    "text/html; charset=utf-8",
                    body,
                    {
                        "Set-Cookie": (
                            f"sidecar_{self.server.server_port}={state.expected_token}; "
                            "Path=/; HttpOnly; Secure; SameSite=None; Partitioned"
                        )
                    },
                )
                return

            if header_token != state.expected_token and query_token != state.expected_token:
                # Simulates gateway.go 401 text/plain nosniff response when auth is missing
                self._send_bytes(
                    401,
                    "Unauthorized",
                    "text/plain; charset=utf-8",
                    b"unauthorized\n",
                    {"X-Content-Type-Options": "nosniff"},
                )
                return

            # Ensure LoopbackSidecarProxy strips conditional headers so no 304 breaks WebResourceResponse
            if "If-None-Match" in self.headers or "If-Modified-Since" in self.headers:
                self._send_bytes(500, "Conditional Header Leaked", "text/plain", b"conditional header present")
                return

            if parsed.path == "/styles.css":
                self._send_bytes(
                    200,
                    "OK",
                    "text/css; charset=utf-8",
                    b".hidden { display: none !important; }\n",
                )
            elif parsed.path == "/app.js":
                self._send_bytes(
                    200,
                    "OK",
                    "application/javascript; charset=utf-8",
                    b'console.log("swarm loaded");\n',
                )
            elif parsed.path == "/logo.png":
                self._send_bytes(
                    200,
                    "OK",
                    "image/png",
                    b"\x89PNG\r\n\x1a\n\x00\x01\x02\x03",
                )
            elif parsed.path == "/empty-ct.css":
                self._send_bytes(
                    200,
                    "OK",
                    None,
                    b"body { color: #fff; }\n",
                )
            elif parsed.path == "/error-check":
                self._send_bytes(
                    404,
                    "Not Found",
                    "text/plain; charset=utf-8",
                    b"missing resource",
                )
            else:
                self._send_bytes(404, "Not Found", "text/plain; charset=utf-8", b"not found")

        def do_GET(self):
            self._handle_get_or_head()

        def do_HEAD(self):
            self._handle_get_or_head()

        def do_POST(self):
            self._record("POST", self.path)
            self._send_bytes(200, "OK", "application/json", b'{"ok":true}')

    return MockGatewayHandler


URI_SHIM = """package android.net;

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;

public class Uri {
    private final String raw;
    private final URI uri;

    private Uri(String raw) {
        this.raw = raw;
        this.uri = URI.create(raw);
    }

    public static Uri parse(String value) {
        if (value == null) throw new NullPointerException("uriString");
        return new Uri(value);
    }

    public String getScheme() { return uri.getScheme(); }
    public String getHost() { return uri.getHost(); }
    public int getPort() { return uri.getPort(); }
    public String getUserInfo() { return uri.getRawUserInfo(); }
    public String getEncodedPath() { return uri.getRawPath(); }
    public String getEncodedQuery() { return uri.getRawQuery(); }

    public String getQueryParameter(String key) {
        String q = uri.getRawQuery();
        if (q == null || q.isEmpty() || key == null) return null;
        String[] pairs = q.split("&");
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            try {
                if (key.equals(URLDecoder.decode(k, "UTF-8"))) {
                    return URLDecoder.decode(v, "UTF-8");
                }
            } catch (UnsupportedEncodingException e) {
                throw new RuntimeException(e);
            }
        }
        return null;
    }

    @Override
    public String toString() { return raw; }
}
"""

LOG_SHIM = """package android.util;

public final class Log {
    public static int d(String tag, String msg) { return 0; }
    public static int i(String tag, String msg) { return 0; }
    public static int w(String tag, String msg) { return 0; }
    public static int w(String tag, String msg, Throwable tr) { return 0; }
    public static int e(String tag, String msg) { return 0; }
}
"""

WEB_RESOURCE_REQUEST_SHIM = """package android.webkit;

import android.net.Uri;
import java.util.Map;

public interface WebResourceRequest {
    Uri getUrl();
    boolean isForMainFrame();
    boolean hasGesture();
    String getMethod();
    Map<String, String> getRequestHeaders();
}
"""

WEB_RESOURCE_RESPONSE_SHIM = """package android.webkit;

import java.io.InputStream;
import java.util.Map;

public class WebResourceResponse {
    private final String mimeType;
    private final String encoding;
    private final int statusCode;
    private final String reasonPhrase;
    private final Map<String, String> responseHeaders;
    private final InputStream data;

    public WebResourceResponse(String mimeType, String encoding, int statusCode,
                               String reasonPhrase, Map<String, String> responseHeaders,
                               InputStream data) {
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("Invalid statusCode: " + statusCode);
        }
        if (reasonPhrase == null || reasonPhrase.trim().isEmpty()) {
            throw new IllegalArgumentException("reasonPhrase must not be empty");
        }
        this.mimeType = mimeType;
        this.encoding = encoding;
        this.statusCode = statusCode;
        this.reasonPhrase = reasonPhrase;
        this.responseHeaders = responseHeaders;
        this.data = data;
    }

    public String getMimeType() { return mimeType; }
    public String getEncoding() { return encoding; }
    public int getStatusCode() { return statusCode; }
    public String getReasonPhrase() { return reasonPhrase; }
    public Map<String, String> getResponseHeaders() { return responseHeaders; }
    public InputStream getData() { return data; }
}
"""

TEST_DRIVER_JAVA = """package com.agy.ui.webview;

import android.net.Uri;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;

import com.agy.util.SensitiveDataRedactor;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class LoopbackSidecarProxyTest {

    private static final class SimpleReq implements WebResourceRequest {
        private final Uri uri;
        private final String method;
        private final Map<String, String> headers;

        SimpleReq(String url, String method, Map<String, String> headers) {
            this.uri = Uri.parse(url);
            this.method = method;
            this.headers = headers;
        }

        @Override public Uri getUrl() { return uri; }
        @Override public boolean isForMainFrame() { return false; }
        @Override public boolean hasGesture() { return false; }
        @Override public String getMethod() { return method; }
        @Override public Map<String, String> getRequestHeaders() { return headers; }
    }

    private static byte[] readAllAndClose(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        try {
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        } finally {
            in.close();
        }
        return out.toByteArray();
    }

    private static void assertTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }

    private static void assertEquals(Object expected, Object actual, String msg) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(msg + " (expected=" + expected + ", actual=" + actual + ")");
        }
    }

    public static void main(String[] args) throws Exception {
        int gwPort = Integer.parseInt(args[0]);
        int devPort = Integer.parseInt(args[1]);
        int enginePort = 45157;
        int sysGatewayPort = 45160;

        LoopbackSidecarProxy proxy = new LoopbackSidecarProxy(enginePort, sysGatewayPort);

        // 1. Untracked port (dev server) is left untouched
        assertEquals(null, proxy.intercept(new SimpleReq(
                "http://localhost:" + devPort + "/main.js", "GET", Collections.<String, String>emptyMap())),
                "untracked dev port should return null");

        // 2. Engine port and SystemGateway port are never captured or intercepted
        assertEquals(null, proxy.intercept(new SimpleReq(
                "http://127.0.0.1:" + enginePort + "/?token=evil", "GET", Collections.<String, String>emptyMap())),
                "enginePort initial should return null");
        assertEquals(null, proxy.intercept(new SimpleReq(
                "http://127.0.0.1:" + enginePort + "/styles.css", "GET", Collections.<String, String>emptyMap())),
                "enginePort follow-up should return null");
        assertEquals(null, proxy.intercept(new SimpleReq(
                "http://127.0.0.1:" + sysGatewayPort + "/?token=evil", "GET", Collections.<String, String>emptyMap())),
                "gatewayPort initial should return null");
        assertEquals(null, proxy.intercept(new SimpleReq(
                "http://127.0.0.1:" + sysGatewayPort + "/gateway/status", "GET", Collections.<String, String>emptyMap())),
                "gatewayPort follow-up should return null");

        // 3. Initial sidecar navigation with URL-encoded token among multiple params captures token and returns null
        WebResourceResponse initial = proxy.intercept(new SimpleReq(
                "http://localhost:" + gwPort + "/?conversationId=conv-42&token=sec%2Bret%3D1",
                "GET", Collections.<String, String>emptyMap()));
        assertEquals(null, initial, "initial ?token= navigation should return null");

        // 4. HTTPS on same port is ignored
        assertEquals(null, proxy.intercept(new SimpleReq(
                "https://localhost:" + gwPort + "/styles.css", "GET", Collections.<String, String>emptyMap())),
                "https scheme should return null");

        // 5. Authenticated CSS GET with conditional + Sec-Fetch-Site headers
        Map<String, String> reqHeaders = new HashMap<>();
        reqHeaders.put("If-None-Match", "\\"etag-1\\"");
        reqHeaders.put("If-Modified-Since", "Wed, 21 Oct 2015 07:28:00 GMT");
        reqHeaders.put("Sec-Fetch-Site", "cross-site");
        reqHeaders.put("Accept-Encoding", "gzip, deflate, br");
        reqHeaders.put("Accept", "text/css,*/*;q=0.1");

        WebResourceResponse cssResp = proxy.intercept(new SimpleReq(
                "http://localhost:" + gwPort + "/styles.css", "GET", reqHeaders));
        assertTrue(cssResp != null, "CSS response must not be null");
        assertEquals(200, cssResp.getStatusCode(), "CSS status");
        assertEquals("OK", cssResp.getReasonPhrase(), "CSS reason");
        assertEquals("text/css", cssResp.getMimeType(), "CSS mime");
        assertEquals("utf-8", cssResp.getEncoding().toLowerCase(), "CSS encoding");
        String cssBody = new String(readAllAndClose(cssResp.getData()), StandardCharsets.UTF_8);
        assertEquals(".hidden { display: none !important; }\\n", cssBody, "CSS body");

        // 6. Authenticated JS GET via 127.0.0.1
        WebResourceResponse jsResp = proxy.intercept(new SimpleReq(
                "http://127.0.0.1:" + gwPort + "/app.js", "GET", Collections.<String, String>emptyMap()));
        assertTrue(jsResp != null, "JS response must not be null");
        assertEquals(200, jsResp.getStatusCode(), "JS status");
        assertEquals("application/javascript", jsResp.getMimeType(), "JS mime");
        assertEquals("utf-8", jsResp.getEncoding().toLowerCase(), "JS encoding");
        String jsBody = new String(readAllAndClose(jsResp.getData()), StandardCharsets.UTF_8);
        assertEquals("console.log(\\"swarm loaded\\");\\n", jsBody, "JS body");

        // 7. Binary asset (PNG) keeps encoding == null
        WebResourceResponse pngResp = proxy.intercept(new SimpleReq(
                "http://localhost:" + gwPort + "/logo.png", "GET", Collections.<String, String>emptyMap()));
        assertTrue(pngResp != null, "PNG response must not be null");
        assertEquals(200, pngResp.getStatusCode(), "PNG status");
        assertEquals("image/png", pngResp.getMimeType(), "PNG mime");
        assertEquals(null, pngResp.getEncoding(), "PNG encoding must be null");
        byte[] pngBytes = readAllAndClose(pngResp.getData());
        assertTrue(Arrays.equals(new byte[]{(byte)0x89, 'P', 'N', 'G', '\\r', '\\n', 0x1a, '\\n', 0, 1, 2, 3}, pngBytes),
                "PNG bytes mismatch");

        // 8. Missing Content-Type falls back to MimeTypes.getMimeType
        WebResourceResponse fallbackResp = proxy.intercept(new SimpleReq(
                "http://localhost:" + gwPort + "/empty-ct.css", "GET", Collections.<String, String>emptyMap()));
        assertTrue(fallbackResp != null, "fallback CT response must not be null");
        assertEquals("text/css", fallbackResp.getMimeType(), "fallback CSS mime");
        assertEquals("UTF-8", fallbackResp.getEncoding(), "fallback CSS encoding");
        assertEquals("body { color: #fff; }\\n",
                new String(readAllAndClose(fallbackResp.getData()), StandardCharsets.UTF_8),
                "fallback CSS body");

        // 9. Error stream (>= 400) works cleanly
        WebResourceResponse errResp = proxy.intercept(new SimpleReq(
                "http://localhost:" + gwPort + "/error-check", "GET", Collections.<String, String>emptyMap()));
        assertTrue(errResp != null, "404 response must not be null");
        assertEquals(404, errResp.getStatusCode(), "404 status");
        assertEquals("Not Found", errResp.getReasonPhrase(), "404 reason");
        assertEquals("text/plain", errResp.getMimeType(), "404 mime");
        assertEquals("missing resource",
                new String(readAllAndClose(errResp.getData()), StandardCharsets.UTF_8),
                "404 error body");

        // 10. HEAD request returns headers with empty stream
        WebResourceResponse headResp = proxy.intercept(new SimpleReq(
                "http://localhost:" + gwPort + "/styles.css", "HEAD", Collections.<String, String>emptyMap()));
        assertTrue(headResp != null, "HEAD response must not be null");
        assertEquals(200, headResp.getStatusCode(), "HEAD status");
        assertEquals("text/css", headResp.getMimeType(), "HEAD mime");
        assertEquals(0, readAllAndClose(headResp.getData()).length, "HEAD body length");

        // 11. POST request is untouched
        assertEquals(null, proxy.intercept(new SimpleReq(
                "http://localhost:" + gwPort + "/_sidecar/get-conversation-metadata",
                "POST", Collections.<String, String>emptyMap())),
                "POST must return null");

        // 12. Redactor strips token query from logs
        String redacted = SensitiveDataRedactor.safeUrl(
                "http://localhost:" + gwPort + "/?conversationId=conv-42&token=sec%2Bret%3D1");
        assertTrue(!redacted.contains("sec") && !redacted.contains("token"),
                "redacted URL leaked token: " + redacted);

        System.out.println("ALL_PROXY_JAVA_TESTS_OK");
    }
}
"""


class TestLoopbackSidecarProxy(unittest.TestCase):

    def setUp(self):
        self.tmp_dir = tempfile.mkdtemp(prefix="test_sidecar_proxy_")

    def tearDown(self):
        shutil.rmtree(self.tmp_dir, ignore_errors=True)

    def _write_java(self, rel_path, content):
        full = os.path.join(self.tmp_dir, "src", rel_path)
        os.makedirs(os.path.dirname(full), exist_ok=True)
        with open(full, "w", encoding="utf-8") as f:
            f.write(content)
        return full

    def test_sidecar_proxy_end_to_end(self):
        state = MockGatewayState("sec+ret=1")
        gw_server = ThreadingHTTPServer(("127.0.0.1", 0), make_gateway_handler(state))
        dev_server = ThreadingHTTPServer(("127.0.0.1", 0), make_gateway_handler(MockGatewayState("unused")))

        gw_thread = threading.Thread(target=gw_server.serve_forever, daemon=True)
        dev_thread = threading.Thread(target=dev_server.serve_forever, daemon=True)
        gw_thread.start()
        dev_thread.start()

        try:
            gw_port = gw_server.server_address[1]
            dev_port = dev_server.server_address[1]

            # Sanity-check mock gateway rejects unauthenticated /styles.css with 401 text/plain nosniff
            import urllib.request
            import urllib.error
            with self.assertRaises(urllib.error.HTTPError) as ctx:
                urllib.request.urlopen(f"http://127.0.0.1:{gw_port}/styles.css")
            self.assertEqual(ctx.exception.code, 401)
            self.assertEqual(ctx.exception.headers.get("X-Content-Type-Options"), "nosniff")
            self.assertTrue(ctx.exception.headers.get("Content-Type", "").startswith("text/plain"))
            ctx.exception.close()

            shim_files = [
                self._write_java("android/net/Uri.java", URI_SHIM),
                self._write_java("android/util/Log.java", LOG_SHIM),
                self._write_java("android/webkit/WebResourceRequest.java", WEB_RESOURCE_REQUEST_SHIM),
                self._write_java("android/webkit/WebResourceResponse.java", WEB_RESOURCE_RESPONSE_SHIM),
                self._write_java("com/agy/ui/webview/LoopbackSidecarProxyTest.java", TEST_DRIVER_JAVA),
            ]
            app_files = [
                os.path.join(JAVA_SRC_DIR, "com", "agy", "util", "MimeTypes.java"),
                os.path.join(JAVA_SRC_DIR, "com", "agy", "util", "SensitiveDataRedactor.java"),
                os.path.join(JAVA_SRC_DIR, "com", "agy", "ui", "webview", "LoopbackSidecarProxy.java"),
            ]

            classes_dir = os.path.join(self.tmp_dir, "classes")
            os.makedirs(classes_dir, exist_ok=True)
            compile_res = subprocess.run(
                ["javac", "-encoding", "UTF-8", "-source", "1.8", "-target", "1.8", "-d", classes_dir]
                + shim_files + app_files,
                capture_output=True,
                text=True,
            )
            self.assertEqual(compile_res.returncode, 0, compile_res.stderr)

            run_res = subprocess.run(
                ["java", "-cp", classes_dir, "com.agy.ui.webview.LoopbackSidecarProxyTest", str(gw_port), str(dev_port)],
                capture_output=True,
                text=True,
            )
            self.assertEqual(run_res.returncode, 0, run_res.stdout + "\n" + run_res.stderr)
            self.assertIn("ALL_PROXY_JAVA_TESTS_OK", run_res.stdout)

            # Verify gateway saw X-Sidecar-Token on /styles.css and /app.js
            css_reqs = [r for r in state.seen_requests if r[0] == "GET" and r[1] == "/styles.css"]
            self.assertEqual(len(css_reqs), 2)  # 1st was unauthenticated 401 sanity check, 2nd from proxy
            self.assertEqual(css_reqs[1][2].get("X-Sidecar-Token"), "sec+ret=1")
        finally:
            gw_server.shutdown()
            dev_server.shutdown()
            gw_server.server_close()
            dev_server.server_close()


if __name__ == "__main__":
    unittest.main()
