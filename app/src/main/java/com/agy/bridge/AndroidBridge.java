package com.agy.bridge;

import android.content.Context;
import android.webkit.JavascriptInterface;

import com.agy.runtime.RuntimeManager;
import com.agy.shell.ShizukuBridge;
import com.agy.theme.ThemeManager;
import com.agy.ui.MainActivity;

import java.lang.ref.WeakReference;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Minimal, low-privilege JavaScript bridge for the app UI. It is visible to
 * embedded frames, so shell, notification, and arbitrary URL capabilities must
 * not be exposed here. Native-only getters below are intentionally unannotated.
 */
public final class AndroidBridge {

    private final Context appContext;
    private final UiBridge ui = new UiBridge();
    private final ThemeBridge themeBridge;
    private final EngineBridge engine;
    private final EngineConfigJson config;
    private final String blobBridgeToken = createBlobBridgeToken();
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

    private static final String PREF_NAME = "agy_custom_prefs";
    private static final String KEY_OPENAI_URL = "openai_gateway_url";
    private static final String KEY_OPENAI_KEY = "openai_gateway_api_key";
    private static final String KEY_OPENAI_MODELS = "openai_gateway_models";
    private static final String KEY_PROJECTS_DIR = "projects_directory";
    public static final String DEFAULT_PROJECTS_DIR = "/storage/emulated/0/Documents/Antigravity";

    @JavascriptInterface
    public String getOpenAiConfig() {
        try {
            android.content.SharedPreferences sp = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            org.json.JSONObject obj = new org.json.JSONObject();
            obj.put("url", sp.getString(KEY_OPENAI_URL, "https://api.openai.com/v1"));
            obj.put("apiKey", sp.getString(KEY_OPENAI_KEY, ""));
            obj.put("models", sp.getString(KEY_OPENAI_MODELS, "gpt-4o,gpt-4o-mini"));
            obj.put("wireProtocol", "openai");
            return obj.toString();
        } catch (Exception e) {
            return "{\"url\":\"https://api.openai.com/v1\",\"apiKey\":\"\",\"models\":\"gpt-4o,gpt-4o-mini\",\"wireProtocol\":\"openai\"}";
        }
    }

    @JavascriptInterface
    public boolean setOpenAiConfig(String url, String apiKey, String models) {
        try {
            if (url == null || url.trim().isEmpty()) {
                url = "https://api.openai.com/v1";
            }
            if (apiKey == null) apiKey = "";
            if (models == null || models.trim().isEmpty()) {
                models = "gpt-4o,gpt-4o-mini";
            }

            android.content.SharedPreferences sp = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit()
                    .putString(KEY_OPENAI_URL, url)
                    .putString(KEY_OPENAI_KEY, apiKey)
                    .putString(KEY_OPENAI_MODELS, models)
                    .apply();

            // Persist to $HOME/.gemini/config/openai_config.json
            java.io.File homeDir = appContext.getFilesDir();
            java.io.File geminiConfigDir = new java.io.File(homeDir, ".gemini/config");
            if (!geminiConfigDir.exists()) geminiConfigDir.mkdirs();
            java.io.File openaiConfigFile = new java.io.File(geminiConfigDir, "openai_config.json");
            org.json.JSONObject cfg = new org.json.JSONObject();
            cfg.put("url", url);
            cfg.put("apiKey", apiKey);
            cfg.put("models", models);
            cfg.put("wireProtocol", "openai");
            com.agy.util.AtomicFileWriter.writeText(openaiConfigFile, cfg.toString(2) + "\n");

            // Persist to $HOME/.gemini/antigravity-cli/settings.json for native language_server gateway
            java.io.File cliDir = new java.io.File(homeDir, ".gemini/antigravity-cli");
            if (!cliDir.exists()) cliDir.mkdirs();
            java.io.File cliSettings = new java.io.File(cliDir, "settings.json");
            org.json.JSONObject settings = new org.json.JSONObject();
            org.json.JSONObject gw = new org.json.JSONObject();
            gw.put("enabled", !apiKey.trim().isEmpty());
            gw.put("baseUrl", url);
            gw.put("wireProtocol", "openai");
            gw.put("apiKey", apiKey);
            settings.put("gateway", gw);

            org.json.JSONArray modelsArr = new org.json.JSONArray();
            for (String m : models.split(",")) {
                String trimmed = m.trim();
                if (!trimmed.isEmpty()) {
                    org.json.JSONObject mo = new org.json.JSONObject();
                    mo.put("modelId", trimmed);
                    mo.put("displayName", trimmed);
                    mo.put("supportsThinking", true);
                    modelsArr.put(mo);
                }
            }
            settings.put("models", modelsArr);
            com.agy.util.AtomicFileWriter.writeText(cliSettings, settings.toString(2) + "\n");

            return true;
        } catch (Exception e) {
            android.util.Log.w("AndroidBridge", "Failed to save OpenAI config", e);
            return false;
        }
    }

    @JavascriptInterface
    public String fetchOpenAiModels(String url, String apiKey) {
        final String[] result = new String[1];
        Thread t = new Thread(() -> {
            try {
                String target = (url == null || url.trim().isEmpty())
                        ? "https://api.openai.com/v1" : url.trim();
                target = target.replaceAll("/+$", "");
                if (!target.endsWith("/models")) {
                    target += "/models";
                }
                java.net.URL targetUrl = new java.net.URL(target);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) targetUrl.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(12000);
                if (apiKey != null && !apiKey.trim().isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
                }
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("User-Agent", "Antigravity/2.0");

                int responseCode = conn.getResponseCode();
                java.io.InputStream is = (responseCode >= 200 && responseCode < 300)
                        ? conn.getInputStream()
                        : conn.getErrorStream();
                if (is == null) {
                    result[0] = "{\"error\":\"HTTP " + responseCode + "\"}";
                    return;
                }
                result[0] = com.agy.util.IoUtil.readAll(is, "UTF-8");
            } catch (Exception e) {
                result[0] = "{\"error\":\"" + (e.getMessage() != null ? e.getMessage().replace("\"", "'") : "Unknown network error") + "\"}";
            }
        });
        t.start();
        try {
            t.join(15000);
        } catch (InterruptedException e) {
            return "{\"error\":\"Request timed out\"}";
        }
        return result[0] != null ? result[0] : "{\"error\":\"No response received\"}";
    }

    @JavascriptInterface
    public String getDefaultProjectsDirectory() {
        try {
            android.content.SharedPreferences sp = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String dir = sp.getString(KEY_PROJECTS_DIR, DEFAULT_PROJECTS_DIR);
            java.io.File f = new java.io.File(dir);
            if (!f.exists()) f.mkdirs();
            return dir;
        } catch (Exception e) {
            return DEFAULT_PROJECTS_DIR;
        }
    }

    @JavascriptInterface
    public boolean setDefaultProjectsDirectory(String path) {
        if (path == null || path.trim().isEmpty()) return false;
        try {
            java.io.File f = new java.io.File(path);
            if (!f.exists()) f.mkdirs();
            android.content.SharedPreferences sp = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_PROJECTS_DIR, path).apply();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @JavascriptInterface
    public boolean createProjectDirectory(String name) {
        if (name == null || name.trim().isEmpty()) return false;
        try {
            String base = getDefaultProjectsDirectory();
            java.io.File proj = new java.io.File(base, name.trim());
            return proj.exists() || proj.mkdirs();
        } catch (Exception e) {
            return false;
        }
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
