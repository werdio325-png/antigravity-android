package com.agy.bridge;

import android.content.Context;
import android.webkit.JavascriptInterface;

import com.agy.BuildConfig;
import com.agy.runtime.RuntimeManager;
import com.agy.shell.ShizukuBridge;
import com.agy.theme.ThemeManager;
import com.agy.ui.MainActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Minimal, low-privilege JavaScript bridge for the app UI. It is visible to
 * embedded frames, so shell, notification, and arbitrary URL capabilities must
 * not be exposed here. Native-only getters below are intentionally unannotated.
 */
public final class AndroidBridge {

    private final UiBridge ui = new UiBridge();
    private final ThemeBridge themeBridge;
    private final EngineBridge engine;
    private final EngineConfigJson config;
    private final String blobBridgeToken = createBlobBridgeToken();
    private final Context appContext;
    private volatile WeakReference<MainActivity> activityRef;

    public AndroidBridge(Context context, RuntimeManager runtime,
                         ShizukuBridge shizuku, ThemeManager theme) {
        this.appContext = context.getApplicationContext();
        this.themeBridge = new ThemeBridge(theme);
        this.engine = new EngineBridge(runtime);
        this.config = new EngineConfigJson(appContext, runtime, engine, shizuku, themeBridge);
    }

    /** Host activity, used to dismiss the native splash once the UI has painted. */
    public void setActivity(MainActivity activity) {
        this.activityRef = activity != null ? new WeakReference<MainActivity>(activity) : null;
        ui.setActivity(activity);
    }

    @JavascriptInterface
    public void onUiReady(String token) {
        if (matchesNativeBridgeToken(token)) {
            ui.onUiReady();
        }
    }

    @JavascriptInterface
    public void openDirectoryPicker() {
        final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
        if (act != null) {
            act.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    act.launchDirectoryPicker();
                }
            });
        }
    }

    @JavascriptInterface
    public void onUiError(String token, final String errorMessage) {
        if (matchesNativeBridgeToken(token)) {
            ui.onUiError(errorMessage);
        }
    }

    @JavascriptInterface
    public boolean setTheme(String mode) {
        return themeBridge.setTheme(mode);
    }

    @JavascriptInterface
    public boolean setThemeByUser(final String mode) {
        return themeBridge.setThemeByUser(mode);
    }

    @JavascriptInterface
    public void onVisualThemeChanged(String visual) {
        themeBridge.onVisualThemeChanged(visual);
    }

    @JavascriptInterface
    public String getTheme() {
        return themeBridge.getTheme();
    }

    @JavascriptInterface
    public boolean isDesktopMode() {
        return ui.isDesktopMode();
    }

    @JavascriptInterface
    public void setDesktopMode(boolean enabled) {
        ui.setDesktopMode(enabled);
    }

    // Native-only helpers used while preparing the trusted app document.
    public boolean isEngineReady() {
        return engine.isReady();
    }

    public int getEnginePort() {
        return engine.getPort();
    }

    public String getEngineBaseUrl() {
        return engine.getBaseUrl();
    }

    public String getCsrfToken() {
        return engine.getCsrfToken();
    }

    public String getConfig() {
        return config.get();
    }

    /** Native-only token inserted into the trusted top-level document. */
    public String getBlobBridgeToken() {
        return blobBridgeToken;
    }

    /** Saves a downloaded blob only for scripts holding the top-level document token. */
    @JavascriptInterface
    public void saveBlob(String token, String base64Data, String filename, String mimeType) {
        if (!matchesNativeBridgeToken(token)) {
            return;
        }
        final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
        if (act != null) {
            com.agy.ui.download.FileDownloader.handleBlobData(
                    act, base64Data, filename, mimeType);
        }
    }

    /**
     * Proxies a JSON command to the local System Gateway using the native gateway token.
     * bodyJson must be a JSON object like {"command":"...","payload":{...}}.
     * Returns a JSON string response. Requires the top-level bridge token.
     */
    @JavascriptInterface
    public String gateway(String token, String bodyJson) {
        if (!matchesNativeBridgeToken(token)) {
            return "{\"ok\":false,\"error\":\"unauthorized\"}";
        }
        HttpURLConnection conn = null;
        try {
            int port = readGatewayPort();
            String gwToken = readGatewayToken();
            URL url = new URL("http://127.0.0.1:" + port + "/gateway");
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(5000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (gwToken != null && !gwToken.isEmpty()) {
                conn.setRequestProperty("X-Agy-Gateway-Token", gwToken);
            }
            byte[] body = (bodyJson == null ? "{}" : bodyJson).getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 400) ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) return "{\"ok\":false,\"error\":\"no response\"}";
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(line);
                }
            }
            String text = sb.toString().trim();
            return text.isEmpty() ? "{\"ok\":false,\"error\":\"empty response\"}" : text;
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "gateway error" : e.getMessage().replace("\"", "'");
            return "{\"ok\":false,\"error\":\"" + msg + "\"}";
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Exception ignored) {}
        }
    }

    private int readGatewayPort() {
        try {
            File f = new File(appContext.getFilesDir(), "logs/gateway.port");
            if (f.isFile()) {
                String s = readSmallFile(f).trim();
                if (s.matches("\\d+")) return Integer.parseInt(s);
            }
        } catch (Exception ignored) {}
        try { return BuildConfig.GATEWAY_PORT; } catch (Throwable ignored) {}
        return 45160;
    }

    private String readGatewayToken() {
        try {
            File f = new File(appContext.getFilesDir(), "logs/gateway.token");
            if (f.isFile()) return readSmallFile(f).trim();
        } catch (Exception ignored) {}
        return null;
    }

    private static String readSmallFile(File f) throws Exception {
        byte[] buf = new byte[512];
        int n;
        try (FileInputStream in = new FileInputStream(f)) {
            n = in.read(buf);
        }
        return n > 0 ? new String(buf, 0, n, StandardCharsets.UTF_8) : "";
    }

    private boolean matchesNativeBridgeToken(String candidate) {
        if (candidate == null) {
            return false;
        }
        try {
            byte[] expected = blobBridgeToken.getBytes(Charset.forName("UTF-8"));
            byte[] actual = candidate.getBytes(Charset.forName("UTF-8"));
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String createBlobBridgeToken() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        char[] hex = "0123456789abcdef".toCharArray();
        char[] encoded = new char[random.length * 2];
        for (int i = 0; i < random.length; i++) {
            int value = random[i] & 0xff;
            encoded[i * 2] = hex[value >>> 4];
            encoded[i * 2 + 1] = hex[value & 0x0f];
        }
        return new String(encoded);
    }
}
