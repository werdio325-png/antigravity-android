package dev.continuum.antigravity.bridge;

import android.content.Context;

import dev.continuum.antigravity.engine.EngineInfo;
import dev.continuum.antigravity.runtime.RuntimeConfig;
import dev.continuum.antigravity.runtime.RuntimeManager;
import dev.continuum.antigravity.shell.ShizukuBridge;

import org.json.JSONObject;

import dev.continuum.antigravity.BuildConfig;

/** Serializes the app config object injected as window.__APP_CONFIG__. */
public final class EngineConfigJson {

    private final Context appContext;
    private final RuntimeManager runtime;
    private final EngineBridge engine;
    private final ShizukuBridge shizuku;
    private final ThemeBridge theme;

    public EngineConfigJson(Context appContext, RuntimeManager runtime, EngineBridge engine,
                            ShizukuBridge shizuku, ThemeBridge theme) {
        this.appContext = appContext;
        this.runtime = runtime;
        this.engine = engine;
        this.shizuku = shizuku;
        this.theme = theme;
    }

    public String get() {
        try {
            JSONObject o = new JSONObject();
            o.put("productName", "antigravity-app");
            o.put("appId", BuildConfig.APPLICATION_ID);
            o.put("csrfToken", engine.getCsrfToken());
            o.put("appVersion", RuntimeConfig.APP_VERSION_NAME);
            o.put("appVersionCode", RuntimeConfig.APP_VERSION_CODE);
            o.put("coreVersion", RuntimeConfig.CORE_VERSION);
            o.put("releaseChannel", RuntimeConfig.DEBUG ? "dev" : "stable");
            o.put("updateRepo", BuildConfig.UPDATE_REPO);
            // Active agent backend. The embedded engine remains the default; ACP
            // state is reported so the UI can show what is really running.
            o.put("backendMode", dev.continuum.antigravity.backend.BackendSettings
                    .mode(appContext).name().toLowerCase());
            if (dev.continuum.antigravity.backend.BackendSettings
                    .mode(appContext) == dev.continuum.antigravity.backend.BackendSettings.Mode.ACP) {
                o.put("acp", dev.continuum.antigravity.acp.AcpRuntime.get(appContext).status());
            }
            // What the embedded engine really is: version, build tag and the
            // model catalogue extracted from the binary at build time.
            o.put("engineInfo", EngineInfo.read(appContext));
            o.put("devMode", RuntimeConfig.DEBUG);
            o.put("enginePort", runtime.getHttpsPort());
            o.put("engineBaseUrl", engine.getBaseUrl());
            o.put("filesDir", appContext.getFilesDir().getAbsolutePath());
            o.put("runtimeDir", runtime.getRuntimeDir().getAbsolutePath());
            o.put("home", appContext.getFilesDir().getAbsolutePath());
            o.put("tmpDir", appContext.getCacheDir().getAbsolutePath());
            o.put("appDataDir", runtime.getAppDataDir().getAbsolutePath());
            o.put("shizukuAvailable", shizuku.isAvailable());
            o.put("theme", theme.getTheme());
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
