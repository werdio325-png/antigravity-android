package com.agy.runtime;

import android.content.Context;

import com.agy.BuildConfig;

/** App-level constants sourced from the generated BuildConfig. */
public final class RuntimeConfig {

    public static final int DEFAULT_HTTPS_PORT = BuildConfig.PORT;
    public static final String CORE_VERSION = BuildConfig.CORE_VERSION;
    public static final int MIN_API = BuildConfig.MIN_API;

    private RuntimeConfig() {
    }

    /** The port from res/values/gen.xml, else the BuildConfig default. */
    public static int httpsPort(Context appContext) {
        if (appContext != null) {
            try {
                int resId = appContext.getResources().getIdentifier(
                        "https_server_port", "integer", appContext.getPackageName());
                if (resId != 0) {
                    return appContext.getResources().getInteger(resId);
                }
            } catch (Exception ignored) {
            }
        }
        return DEFAULT_HTTPS_PORT;
    }
}
