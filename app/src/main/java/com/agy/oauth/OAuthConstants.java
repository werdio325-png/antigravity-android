package com.agy.oauth;

import com.agy.BuildConfig;

/** OAuth/Custom-Tab constants, inlined (no androidx.browser dependency). */
public final class OAuthConstants {

    public static final String TAG = "OAuthManager";

    public static final String CHROME_PACKAGE = "com.android.chrome";

    // Custom Tabs protocol extras (androidx.browser constants, inlined).
    public static final String EXTRA_SESSION = "android.support.customtabs.extra.SESSION";
    public static final String EXTRA_TOOLBAR_COLOR =
            "android.support.customtabs.extra.TOOLBAR_COLOR";
    public static final String EXTRA_APP_ID = "com.android.browser.application_id";
    public static final int TOOLBAR_COLOR_DARK = 0xFF101010;

    public static final String CALLBACK_SCHEME = BuildConfig.OAUTH_SCHEME;
    /** Host emitted by the official antigravity.google success page. */
    public static final String CALLBACK_HOST = "oauth-success";
    /** Legacy host kept for backward compatibility. */
    public static final String CALLBACK_HOST_COMPAT = "auth-success";

    /**
     * Where the standalone core persists the OAuth token. HOME is the app's
     * getFilesDir(), so the file is
     * {@code <filesDir>/.gemini/jetski-standalone-oauth-token}.
     */
    public static final String TOKEN_DIR = ".gemini";
    public static final String TOKEN_NAME = "jetski-standalone-oauth-token";

    private OAuthConstants() {
    }
}
