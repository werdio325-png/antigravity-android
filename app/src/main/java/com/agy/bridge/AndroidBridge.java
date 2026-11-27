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
        initAnthropicBridgeIfConfigured();
    }

    private void initAnthropicBridgeIfConfigured() {
        try {
            android.content.SharedPreferences sp = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY_OPENAI_CONFIGS, "");
            if (raw != null && !raw.trim().isEmpty()) {
                org.json.JSONArray arr = new org.json.JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    org.json.JSONObject obj = arr.optJSONObject(i);
                    if (obj != null && "anthropic-setup-token".equals(obj.optString("providerType", ""))) {
                        String tok = obj.optString("apiKey", "").trim();
                        if (!tok.isEmpty()) {
                            AnthropicBridgeServer srv = AnthropicBridgeServer.getInstance(appContext);
                            srv.setToken(tok);
                            obj.put("url", srv.getBaseUrl());
                            sp.edit().putString(KEY_OPENAI_CONFIGS, arr.toString()).apply();
                            break;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
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
    private static final String KEY_OPENAI_CONFIGS = "openai_gateway_configs";
    private static final int MAX_OPENAI_CONFIGS = 5;
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
    public String getOpenAiConfigs() {
        try {
            android.content.SharedPreferences sp = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            String raw = sp.getString(KEY_OPENAI_CONFIGS, "");
            if (raw != null && !raw.trim().isEmpty()) {
                org.json.JSONArray arr = new org.json.JSONArray(raw);
                if (arr.length() > 0) {
                    return arr.toString();
                }
            }
            // Fallback: wrap legacy single config
            org.json.JSONArray fallback = new org.json.JSONArray();
            org.json.JSONObject item = new org.json.JSONObject();
            item.put("id", "api-1");
            item.put("name", "API 1");
            item.put("url", sp.getString(KEY_OPENAI_URL, "https://api.openai.com/v1"));
            item.put("apiKey", sp.getString(KEY_OPENAI_KEY, ""));
            String modsStr = sp.getString(KEY_OPENAI_MODELS, "gpt-4o,gpt-4o-mini");
            String firstModel = "gpt-4o";
            org.json.JSONArray modsArr = new org.json.JSONArray();
            for (String m : modsStr.split(",")) {
                String t = m.trim();
                if (!t.isEmpty()) {
                    if (modsArr.length() == 0) firstModel = t;
                    modsArr.put(t);
                }
            }
            item.put("model", firstModel);
            item.put("models", modsArr);
            fallback.put(item);
            return fallback.toString();
        } catch (Exception e) {
            return "[{\"id\":\"api-1\",\"name\":\"API 1\",\"url\":\"https://api.openai.com/v1\",\"apiKey\":\"\",\"model\":\"gpt-4o\",\"models\":[\"gpt-4o\",\"gpt-4o-mini\"]}]";
        }
    }

    @JavascriptInterface
    public boolean setOpenAiConfig(String url, String apiKey, String models) {
        return persistGatewayConfig(url, apiKey, models, null);
    }

    @JavascriptInterface
    public String getAnthropicBridgeUrl() {
        return AnthropicBridgeServer.getInstance(appContext).getBaseUrl();
    }

    @JavascriptInterface
    public String getLastClaudeUsage() {
        return AnthropicBridgeServer.getInstance(appContext).getUsageJson();
    }

    @JavascriptInterface
    public String getClaudeUsage(String token) {
        final String[] res = new String[1];
        Thread t = new Thread(() -> res[0] = AnthropicBridgeServer.getInstance(appContext).fetchUsageJson(token));
        t.start();
        try {
            t.join(12000);
        } catch (InterruptedException e) {
            return "{\"error\":\"Timed out\"}";
        }
        return res[0] != null ? res[0] : AnthropicBridgeServer.getInstance(appContext).getUsageJson();
    }

    @JavascriptInterface
    public boolean setOpenAiConfigs(String configsJson, int activeIndex) {
        try {
            org.json.JSONArray input = new org.json.JSONArray(configsJson == null ? "[]" : configsJson);
            org.json.JSONArray capped = new org.json.JSONArray();
            int limit = Math.min(input.length(), MAX_OPENAI_CONFIGS);
            String anthropicToken = "";
            for (int i = 0; i < limit; i++) {
                org.json.JSONObject src = input.optJSONObject(i);
                if (src == null) continue;
                org.json.JSONObject clean = new org.json.JSONObject();
                String id = src.optString("id", "api-" + (i + 1));
                String name = src.optString("name", "API " + (i + 1));
                String pType = src.optString("providerType", "openai");
                String url = src.optString("url", "https://api.openai.com/v1").trim();
                if (url.isEmpty()) url = "https://api.openai.com/v1";
                String key = src.optString("apiKey", "");
                String model = src.optString("model", "gpt-4o");
                org.json.JSONArray mods = src.optJSONArray("models");
                if (mods == null) mods = new org.json.JSONArray();
                if ("anthropic-setup-token".equals(pType) && !key.trim().isEmpty()) {
                    anthropicToken = key.trim();
                    url = AnthropicBridgeServer.getInstance(appContext).getBaseUrl();
                }
                clean.put("id", id);
                clean.put("name", name.isEmpty() ? ("API " + (i + 1)) : name);
                clean.put("providerType", pType);
                clean.put("url", url);
                clean.put("apiKey", key);
                clean.put("model", model);
                clean.put("models", mods);
                capped.put(clean);
            }

            if (!anthropicToken.isEmpty()) {
                AnthropicBridgeServer.getInstance(appContext).setToken(anthropicToken);
            }

            android.content.SharedPreferences sp = appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
            sp.edit().putString(KEY_OPENAI_CONFIGS, capped.toString()).apply();

            java.io.File homeDir = appContext.getFilesDir();
            java.io.File geminiConfigDir = new java.io.File(homeDir, ".gemini/config");
            if (!geminiConfigDir.exists()) geminiConfigDir.mkdirs();
            java.io.File configsFile = new java.io.File(geminiConfigDir, "openai_configs.json");
            com.agy.util.AtomicFileWriter.writeText(configsFile, capped.toString(2) + "\n");

            if (capped.length() == 0) {
                return persistGatewayConfig("https://api.openai.com/v1", "", "gpt-4o,gpt-4o-mini", capped);
            }
            int idx = (activeIndex >= 0 && activeIndex < capped.length()) ? activeIndex : 0;
            org.json.JSONObject active = capped.getJSONObject(idx);
            // If active has empty key, prefer first entry that has a key for native gateway
            if (active.optString("apiKey", "").trim().isEmpty()) {
                for (int i = 0; i < capped.length(); i++) {
                    if (!capped.getJSONObject(i).optString("apiKey", "").trim().isEmpty()) {
                        active = capped.getJSONObject(i);
                        break;
                    }
                }
            }
            String activeUrl = active.optString("url", "https://api.openai.com/v1");
            String activeKey = active.optString("apiKey", "");
            StringBuilder modsSb = new StringBuilder();
            org.json.JSONArray mArr = active.optJSONArray("models");
            if (mArr != null) {
                for (int j = 0; j < mArr.length(); j++) {
                    String ms = mArr.optString(j, "").trim();
                    if (!ms.isEmpty()) {
                        if (modsSb.length() > 0) modsSb.append(",");
                        modsSb.append(ms);
                    }
                }
            }
            if (modsSb.length() == 0) {
                modsSb.append(active.optString("model", "gpt-4o"));
            }
            return persistGatewayConfig(activeUrl, activeKey, modsSb.toString(), capped);
        } catch (Exception e) {
            android.util.Log.w("AndroidBridge", "Failed to save OpenAI configs", e);
            return false;
        }
    }

    private boolean persistGatewayConfig(String url, String apiKey, String models, org.json.JSONArray allConfigs) {
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
            if (allConfigs != null) {
                settings.put("gateways", allConfigs);
            }

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
                    String k = apiKey.trim();
                    if (target.contains("api.anthropic.com") || k.startsWith("sk-ant-")) {
                        conn.setRequestProperty("anthropic-version", "2023-06-01");
                        if (k.startsWith("sk-ant-oat")) {
                            conn.setRequestProperty("Authorization", "Bearer " + k);
                            conn.setRequestProperty("anthropic-beta", "oauth-2025-04-20");
                        } else {
                            conn.setRequestProperty("x-api-key", k);
                        }
                    } else {
                        conn.setRequestProperty("Authorization", "Bearer " + k);
                    }
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

    @JavascriptInterface
    public void exportChatBackup() {
        final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
        if (act != null) {
            act.runOnUiThread(act::launchChatBackupCreate);
        }
    }

    @JavascriptInterface
    public void importChatBackup() {
        final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
        if (act != null) {
            act.runOnUiThread(act::launchChatBackupRestore);
        }
    }

    public String exportChatsToUri(android.net.Uri destUri) {
        if (destUri == null) return "{\"ok\":false,\"error\":\"Cancelled\"}";
        java.io.File geminiDir = new java.io.File(appContext.getFilesDir(), ".gemini");
        if (!geminiDir.isDirectory()) {
            return "{\"ok\":false,\"error\":\"No Antigravity data directory found\"}";
        }
        String[] targets = {
                "antigravity",
                "antigravity-app",
                "config/openai_configs.json",
                "config/openai_config.json"
        };
        int count = 0;
        try (java.io.OutputStream os = appContext.getContentResolver().openOutputStream(destUri);
             java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(new java.io.BufferedOutputStream(os))) {
            byte[] buf = new byte[8192];
            for (String rel : targets) {
                java.io.File f = new java.io.File(geminiDir, rel);
                if (!f.exists()) continue;
                count += zipRecursive(f, rel, zos, buf);
            }
            zos.finish();
            return "{\"ok\":true,\"action\":\"export\",\"files\":" + count + "}";
        } catch (Exception e) {
            android.util.Log.w("AndroidBridge", "Chat backup export failed", e);
            return "{\"ok\":false,\"action\":\"export\",\"error\":" + org.json.JSONObject.quote(String.valueOf(e.getMessage())) + "}";
        }
    }

    private static int zipRecursive(java.io.File file, String entryName, java.util.zip.ZipOutputStream zos, byte[] buf) throws java.io.IOException {
        if (file.isDirectory()) {
            java.io.File[] children = file.listFiles();
            if (children == null) return 0;
            int total = 0;
            for (java.io.File child : children) {
                total += zipRecursive(child, entryName + "/" + child.getName(), zos, buf);
            }
            return total;
        }
        java.util.zip.ZipEntry ze = new java.util.zip.ZipEntry(entryName);
        ze.setTime(file.lastModified());
        zos.putNextEntry(ze);
        try (java.io.InputStream in = new java.io.BufferedInputStream(new java.io.FileInputStream(file))) {
            int n;
            while ((n = in.read(buf)) > 0) {
                zos.write(buf, 0, n);
            }
        }
        zos.closeEntry();
        return 1;
    }

    public String importChatsFromUri(android.net.Uri srcUri) {
        if (srcUri == null) return "{\"ok\":false,\"error\":\"Cancelled\"}";
        java.io.File geminiDir = new java.io.File(appContext.getFilesDir(), ".gemini");
        if (!geminiDir.exists()) geminiDir.mkdirs();
        int count = 0;
        try {
            String canonicalRoot = geminiDir.getCanonicalPath() + java.io.File.separator;
            try (java.io.InputStream is = appContext.getContentResolver().openInputStream(srcUri);
                 java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(new java.io.BufferedInputStream(is))) {
                byte[] buf = new byte[8192];
                java.util.zip.ZipEntry ze;
                while ((ze = zis.getNextEntry()) != null) {
                    if (ze.isDirectory()) continue;
                    String name = ze.getName().replace('\\', '/');
                    while (name.startsWith("/")) name = name.substring(1);
                    if (name.isEmpty()) continue;
                    java.io.File outFile = new java.io.File(geminiDir, name);
                    if (!outFile.getCanonicalPath().startsWith(canonicalRoot)) {
                        throw new SecurityException("Blocked invalid zip entry: " + name);
                    }
                    java.io.File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    try (java.io.OutputStream fos = new java.io.BufferedOutputStream(new java.io.FileOutputStream(outFile))) {
                        int n;
                        while ((n = zis.read(buf)) > 0) {
                            fos.write(buf, 0, n);
                        }
                    }
                    zis.closeEntry();
                    count++;
                }
            }
            // Reload OpenAI/Claude configs into SharedPreferences if restored
            java.io.File restoredConfigs = new java.io.File(geminiDir, "config/openai_configs.json");
            if (restoredConfigs.isFile()) {
                String raw = com.agy.util.IoUtil.readAll(new java.io.FileInputStream(restoredConfigs), "UTF-8");
                if (raw != null && !raw.trim().isEmpty()) {
                    appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                            .edit().putString(KEY_OPENAI_CONFIGS, raw.trim()).apply();
                    initAnthropicBridgeIfConfigured();
                }
            }
            return "{\"ok\":true,\"action\":\"import\",\"files\":" + count + "}";
        } catch (Exception e) {
            android.util.Log.w("AndroidBridge", "Chat backup restore failed", e);
            return "{\"ok\":false,\"action\":\"import\",\"error\":" + org.json.JSONObject.quote(String.valueOf(e.getMessage())) + "}";
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
