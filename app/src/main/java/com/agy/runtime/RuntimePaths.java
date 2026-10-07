package com.agy.runtime;

import android.content.Context;

import java.io.File;

/** Resolves the app-private runtime/web/user-state directories and asset names. */
public final class RuntimePaths {

    public static final String RUNTIME_ASSET = "runtime";
    public static final String WEB_ASSET = "web";
    public static final String MANIFEST_ASSET = RUNTIME_ASSET + "/manifest.json";
    public static final String SEED_ASSET = RUNTIME_ASSET + "/seed";
    /** Written after runtime extraction + manifest verification. Skips re-extract. */
    public static final String RUNTIME_MARKER = ".installed";
    public static final String WEB_MARKER = ".web_installed";

    public static final String BIN_DIR = "runtime/bin";
    public static final String LIB_DIR = "runtime/lib";
    public static final String CERT_REL = "runtime/certs/ca-certificates.crt";
    public static final String LOADER_NAME = "libloader.so";
    public static final String CORE_NAME = "liblanguage_server.so";

    /**
     * The core resolves its state under {@code $HOME/.gemini/<app_data_dir>}
     * (HOME = getFilesDir(), app_data_dir = "antigravity-app"). It resolves
     * projects under {@code $HOME/.gemini/config/projects}.
     */
    public static final String GEMINI_DIR = ".gemini";
    public static final String APP_DATA_DIR_NAME = "antigravity-app";
    public static final String CONFIG_PROJECTS = "config/projects";

    private final Context appContext;

    public RuntimePaths(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public File getRuntimeDir() {
        return new File(appContext.getFilesDir(), RUNTIME_ASSET);
    }

    public File getWebDir() {
        return new File(appContext.getFilesDir(), WEB_ASSET);
    }

    public File getBinDir() {
        return new File(appContext.getFilesDir(), BIN_DIR);
    }

    public File getLibDir() {
        return new File(appContext.getFilesDir(), LIB_DIR);
    }

    public File getCertFile() {
        return new File(appContext.getFilesDir(), CERT_REL);
    }

    public File getGeminiDir() {
        return new File(appContext.getFilesDir(), GEMINI_DIR);
    }

    public File getAppDataDir() {
        return new File(getGeminiDir(), APP_DATA_DIR_NAME);
    }

    public File getProjectsDir() {
        return new File(getGeminiDir(), CONFIG_PROJECTS);
    }
}
