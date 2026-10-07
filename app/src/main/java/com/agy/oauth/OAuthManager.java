package com.agy.oauth;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.io.File;

/**
 * OAuth is delegated to an external browser (Google blocks WebView login).
 * This is the thin facade over the matcher, launcher, token store and deep link.
 */
public final class OAuthManager {

    private final AuthUrlMatcher matcher = new AuthUrlMatcher();
    private final CustomTabLauncher launcher = new CustomTabLauncher();

    /** Null-safe handle to the core's persisted OAuth token file. */
    public static File tokenFile(Context context) {
        return OAuthTokenStore.tokenFile(context);
    }

    public static boolean hasToken(Context context) {
        return OAuthTokenStore.hasToken(context);
    }

    /** True when the URL must leave the WebView and go to the system browser. */
    public boolean isAuthUrl(String url) {
        return matcher.isAuthUrl(url);
    }

    /**
     * Intercepts an auth URL. Returns true if it was handled (opened externally),
     * false if the WebView should load it normally.
     */
    public boolean intercept(Context context, String url) {
        boolean auth = matcher.isAuthUrl(url);
        Log.i(OAuthConstants.TAG, "intercept isAuthUrl=" + auth + " url=" + url);
        if (!auth) {
            return false;
        }
        Log.i(OAuthConstants.TAG, "isAuthUrl matched -> external browser: " + url);
        return launcher.openInBrowser(context, url);
    }

    /** Opens the URL as a Chrome Custom Tab, falling back to any browser. */
    public boolean openInBrowser(Context context, String url) {
        return launcher.openInBrowser(context, url);
    }

    public static String getCallbackScheme(Context context) {
        return DeepLinkHandler.getCallbackScheme(context);
    }

    public boolean handleDeepLink(Activity activity, Intent intent, Runnable onSuccess) {
        return DeepLinkHandler.handleDeepLink(activity, intent, onSuccess);
    }
}
