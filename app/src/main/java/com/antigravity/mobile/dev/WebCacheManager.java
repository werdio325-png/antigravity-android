package com.antigravity.mobile.dev;

import android.content.Context;
import android.net.Uri;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

public class WebCacheManager {
    private final Context context;
    private static SSLSocketFactory loopbackSslSocketFactory;
    private static final HostnameVerifier LOOPBACK_HOSTNAME_VERIFIER = (hostname, session) -> true;

    public WebCacheManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public void prewarm() {
        new Thread(() -> {
            try {
                getLoopbackSslSocketFactory();
            } catch (Exception ignored) {}
        }, "SSLPrewarm").start();
    }

    private static volatile String cachedIndexHtmlTemplate = null;

    private String getIndexHtmlTemplate() {
        File override = new File(context.getFilesDir(), "web/index.html");
        if (override.exists()) {
            try (InputStream in = new FileInputStream(override)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) {
                    baos.write(buf, 0, r);
                }
                return new String(baos.toByteArray(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                Log.e("WebCacheManager", "Failed to load override index.html", e);
            }
        }
        if (cachedIndexHtmlTemplate != null) {
            return cachedIndexHtmlTemplate;
        }
        try (InputStream in = context.getAssets().open("web/index.html")) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                baos.write(buf, 0, r);
            }
            String raw = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            String themeStyle = "<style id=\"native-theme-init\">\n" +
                "  html, body { margin: 0; padding: 0; }\n" +
                "  #root { min-height: 100vh; }\n" +
                "  @media (prefers-color-scheme: dark) {\n" +
                "    html:not(.light), body:not(.theme-light) { background-color: #101010; color-scheme: dark; }\n" +
                "  }\n" +
                "  @media (prefers-color-scheme: light) {\n" +
                "    html:not(.dark), body:not(.dark) { background-color: #ffffff; color-scheme: light; }\n" +
                "  }\n" +
                "  body.dark, html.dark { background-color: #101010 !important; color-scheme: dark !important; }\n" +
                "  body.theme-light, html.light { background-color: #ffffff !important; color-scheme: light !important; }\n" +
                "</style>\n";
            String configTag = "<script>window.__APP_CONFIG__ = {\"productName\":\"antigravity\",\"csrfToken\":\"%CSRF_TOKEN%\",\"appVersion\":\"%APP_VERSION%\",\"devMode\":false};</script>\n";
            String bridgeTag = "<script>\n" +
                "  (function() {\n" +
                "    var notified = false;\n" +
                "    function checkMounted() {\n" +
                "      if (notified) return;\n" +
                "      var root = document.getElementById('root');\n" +
                "      var hasInput = document.querySelector('textarea, [contenteditable=\"true\"], input, [role=\"textbox\"]');\n" +
                "      var hasContent = root && root.firstElementChild && (root.children.length > 0 || (root.innerText || '').trim().length > 0);\n" +
                "      if (hasInput || hasContent) {\n" +
                "        notified = true;\n" +
                "        requestAnimationFrame(function() {\n" +
                "          requestAnimationFrame(function() {\n" +
                "            console.log('[PERF] React UI fully mounted and drawn into GPU! Notifying native bridge.');\n" +
                "            if (window.AntigravityNative && window.AntigravityNative.onInterfaceRendered) {\n" +
                "              window.AntigravityNative.onInterfaceRendered();\n" +
                "            }\n" +
                "          });\n" +
                "        });\n" +
                "      }\n" +
                "    }\n" +
                "    var obs = new MutationObserver(checkMounted);\n" +
                "    obs.observe(document.documentElement, { childList: true, subtree: true, characterData: true });\n" +
                "    setInterval(checkMounted, 40);\n" +
                "\n" +
                "    var lastSentTheme = null;\n" +
                "    function syncTheme() {\n" +
                "      var isDark = false;\n" +
                "      if (document.body && document.body.classList.contains('dark')) {\n" +
                "        isDark = true;\n" +
                "      } else if (document.body && document.body.classList.contains('theme-light')) {\n" +
                "        isDark = false;\n" +
                "      } else if (window.matchMedia) {\n" +
                "        isDark = window.matchMedia('(prefers-color-scheme: dark)').matches;\n" +
                "      }\n" +
                "      if (lastSentTheme !== isDark) {\n" +
                "        lastSentTheme = isDark;\n" +
                "        if (window.AntigravityNative && window.AntigravityNative.onThemeChanged) {\n" +
                "          window.AntigravityNative.onThemeChanged(isDark);\n" +
                "        }\n" +
                "      }\n" +
                "    }\n" +
                "    if (window.matchMedia) {\n" +
                "      window.matchMedia('(prefers-color-scheme: dark)').addListener(syncTheme);\n" +
                "    }\n" +
                "    var themeObs = new MutationObserver(syncTheme);\n" +
                "    function initThemeWatch() {\n" +
                "      if (document.body) {\n" +
                "        themeObs.observe(document.body, { attributes: true, attributeFilter: ['class'] });\n" +
                "        syncTheme();\n" +
                "      } else {\n" +
                "        document.addEventListener('DOMContentLoaded', function() {\n" +
                "          if (document.body) {\n" +
                "            themeObs.observe(document.body, { attributes: true, attributeFilter: ['class'] });\n" +
                "            syncTheme();\n" +
                "          }\n" +
                "        });\n" +
                "      }\n" +
                "    }\n" +
                "    initThemeWatch();\n" +
                "  })();\n" +
                "</script>\n";

            if (!raw.contains("native-theme-init")) {
                raw = raw.replace("<head>", "<head>\n    " + themeStyle);
            }
            if (!raw.contains("window.__APP_CONFIG__ =")) {
                raw = raw.replace("<head>", "<head>\n    " + configTag);
            }
            if (!raw.contains("AntigravityNative")) {
                raw = raw.replace("</body>", "  " + bridgeTag + "  </body>");
            }
            cachedIndexHtmlTemplate = raw;
            return cachedIndexHtmlTemplate;
        } catch (Exception e) {
            Log.e("WebCacheManager", "Failed to load index.html from assets", e);
            return null;
        }
    }

    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
        if (req != null && req.getUrl() != null) {
            Uri uri = req.getUrl();
            String host = uri.getHost();
            if (host != null && (host.contains("fonts.googleapis.com") || host.contains("fonts.gstatic.com"))) {
                return new WebResourceResponse("text/css", "UTF-8", new ByteArrayInputStream(new byte[0]));
            }
            if (("127.0.0.1".equals(host) || "localhost".equals(host)) && "GET".equalsIgnoreCase(req.getMethod())) {
                String path = uri.getPath();
                if (path == null || path.isEmpty() || "/".equals(path) || "/index.html".equals(path)) {
                    WebResourceResponse indexResp = handleIndexHtml(uri);
                    if (indexResp != null) return indexResp;
                } else if (isStaticAsset(path)) {
                    WebResourceResponse assetResp = handleCachedAsset(uri);
                    if (assetResp != null) return assetResp;
                }
            }
        }
        return null;
    }

    public WebResourceResponse handleIndexHtml(Uri uri) {
        try {
            String template = getIndexHtmlTemplate();
            if (template == null) return null;

            String csrf = uri.getQueryParameter("csrf_token");
            if (csrf == null || csrf.isEmpty()) {
                csrf = CoreServerService.csrfToken;
            }
            if (csrf == null) csrf = "";

            String html = template
                .replace("%CSRF_TOKEN%", csrf)
                .replace("%APP_VERSION%", CoreServerService.ENGINE_VERSION);
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);

            Map<String, String> headers = new HashMap<>();
            headers.put("Content-Type", "text/html; charset=UTF-8");
            headers.put("Cache-Control", "no-cache, no-store, must-revalidate");
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Content-Length", String.valueOf(bytes.length));
            PerfLogger.log("handleIndexHtml: served root index.html from assets (0 ms)");
            return new WebResourceResponse("text/html", "UTF-8", 200, "OK", headers, new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            return null;
        }
    }

    public WebResourceResponse handleCachedAsset(Uri uri) {
        try {
            String path = uri.getPath();
            if (path == null) return null;

            String mimeType = getMimeType(path);
            String encoding = getEncoding(mimeType);

            // 1. First check APK assets directly (Zero disk writes, zero network delay)
            String assetPath = "web" + (path.startsWith("/") ? path : "/" + path);
            try {
                InputStream assetStream = context.getAssets().open(assetPath);
                Map<String, String> headers = new HashMap<>();
                headers.put("Cache-Control", "public, max-age=31536000, immutable");
                headers.put("Access-Control-Allow-Origin", "*");
                PerfLogger.log("handleCachedAsset [asset]: " + path);
                return new WebResourceResponse(mimeType, encoding, 200, "OK", headers, assetStream);
            } catch (Exception ignored) {
                // Not found in APK assets, fallback to loopback disk cache
            }

            // 2. Loopback server disk cache
            File cacheDir = new File(context.getFilesDir(), "web_cache/" + CoreServerService.ENGINE_VERSION);
            if (!cacheDir.exists()) {
                cacheDir.mkdirs();
            }
            String fullPath = path + (uri.getQuery() != null ? "?" + uri.getQuery() : "");
            String cacheKey = (fullPath.hashCode() & 0x7fffffff) + "_" + path.replaceAll("[^a-zA-Z0-9._-]", "_");
            if (cacheKey.length() > 80) {
                cacheKey = cacheKey.substring(0, 80);
            }
            File cacheFile = new File(cacheDir, cacheKey);

            if (!cacheFile.exists() || cacheFile.length() == 0) {
                String urlStr = "https://127.0.0.1:" + CoreServerService.PORT + path;
                if (uri.getQuery() != null) {
                    urlStr += "?" + uri.getQuery();
                }
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection(java.net.Proxy.NO_PROXY);
                if (conn instanceof HttpsURLConnection) {
                    ((HttpsURLConnection) conn).setSSLSocketFactory(getLoopbackSslSocketFactory());
                    ((HttpsURLConnection) conn).setHostnameVerifier(LOOPBACK_HOSTNAME_VERIFIER);
                }
                conn.setConnectTimeout(1000);
                conn.setReadTimeout(4000);
                if (conn.getResponseCode() != 200) {
                    return null;
                }
                File tmpFile = new File(cacheDir, cacheKey + "_" + Thread.currentThread().getId() + ".tmp");
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(tmpFile)) {
                    byte[] buf = new byte[16384];
                    int r;
                    while ((r = in.read(buf)) != -1) {
                        out.write(buf, 0, r);
                    }
                }
                if (!tmpFile.renameTo(cacheFile)) {
                    if (!cacheFile.exists()) {
                        tmpFile.delete();
                        return null;
                    }
                    tmpFile.delete();
                }
            }

            Map<String, String> headers = new HashMap<>();
            headers.put("Cache-Control", "public, max-age=31536000, immutable");
            headers.put("Access-Control-Allow-Origin", "*");
            headers.put("Content-Length", String.valueOf(cacheFile.length()));
            headers.put("ETag", "\"" + cacheFile.lastModified() + "\"");
            headers.put("Last-Modified", "Tue, 01 Jan 1980 00:00:00 GMT");
            PerfLogger.log("handleCachedAsset [network-cache]: " + path + " (" + (cacheFile.length() / 1024) + " KB)");
            return new WebResourceResponse(mimeType, encoding, 200, "OK", headers, new BufferedInputStream(new FileInputStream(cacheFile), 65536));
        } catch (Exception e) {
            return null;
        }
    }

    public void cleanOldWebCaches() {
        new Thread(() -> {
            try {
                File rootWebCache = new File(context.getFilesDir(), "web_cache");
                if (!rootWebCache.exists()) return;
                File[] subDirs = rootWebCache.listFiles();
                if (subDirs != null) {
                    for (File dir : subDirs) {
                        if (dir.isDirectory() && !dir.getName().equals(CoreServerService.ENGINE_VERSION)) {
                            deleteDir(dir);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }, "WebCacheCleaner").start();
    }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.exists()) return;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDir(f);
                else f.delete();
            }
        }
        dir.delete();
    }

    private static synchronized SSLSocketFactory getLoopbackSslSocketFactory() {
        if (loopbackSslSocketFactory == null) {
            try {
                TrustManager[] trustAll = new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
                };
                SSLContext sc = SSLContext.getInstance("TLS");
                sc.init(null, trustAll, new SecureRandom());
                loopbackSslSocketFactory = sc.getSocketFactory();
            } catch (Exception ignored) {}
        }
        return loopbackSslSocketFactory;
    }

    public static String getMimeType(String path) {
        if (path == null) return "application/octet-stream";
        String lower = path.toLowerCase();
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "application/javascript";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".html") || lower.endsWith(".htm")) return "text/html";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".ico")) return "image/x-icon";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".ttf")) return "font/ttf";
        if (lower.endsWith(".json")) return "application/json";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".ogg")) return "audio/ogg";
        return "application/octet-stream";
    }

    public static String getEncoding(String mimeType) {
        if (mimeType == null) return null;
        if (mimeType.startsWith("text/") || mimeType.equals("application/javascript") || mimeType.equals("application/json")) {
            return "UTF-8";
        }
        return null;
    }

    public static boolean isStaticAsset(String path) {
        if (path == null) return false;
        if (path.startsWith("/api/") || path.startsWith("/_private/") || path.startsWith("/ws") || path.contains("/auth/")) {
            return false;
        }
        String lower = path.toLowerCase();
        return lower.endsWith(".js") || lower.endsWith(".mjs") ||
               lower.endsWith(".css") ||
               lower.endsWith(".svg") || lower.endsWith(".png") || lower.endsWith(".jpg") ||
               lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".ico") ||
               lower.endsWith(".woff") || lower.endsWith(".woff2") || lower.endsWith(".ttf") ||
               lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg");
    }
}
