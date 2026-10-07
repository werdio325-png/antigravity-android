package com.agy.oauth;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

/**
 * Handles the antigravity://auth-success deep link delivered to MainActivity.
 * Returns true when the intent was the auth callback.
 */
public final class DeepLinkHandler {

    private DeepLinkHandler() {
    }

    public static String getCallbackScheme(Context context) {
        if (context != null) {
            try {
                int resId = context.getResources().getIdentifier(
                        "oauth_scheme", "string", context.getPackageName());
                if (resId != 0) {
                    return context.getString(resId);
                }
            } catch (Exception ignored) {
            }
        }
        return OAuthConstants.CALLBACK_SCHEME;
    }

    public static boolean handleDeepLink(Activity activity, Intent intent, Runnable onSuccess) {
        if (activity == null || intent == null || intent.getData() == null) {
            return false;
        }
        Uri data = intent.getData();
        String expectedScheme = getCallbackScheme(activity);
        String host = data.getHost();
        boolean hostOk = OAuthConstants.CALLBACK_HOST.equals(host)
                || OAuthConstants.CALLBACK_HOST_COMPAT.equals(host);
        if (!expectedScheme.equalsIgnoreCase(data.getScheme()) || !hostOk) {
            return false;
        }
        Log.i(OAuthConstants.TAG, "auth-success deep link received: " + data
                + " tokenPresent=" + OAuthTokenStore.hasToken(activity));
        if (onSuccess != null) {
            onSuccess.run();
        }
        return true;
    }
}
