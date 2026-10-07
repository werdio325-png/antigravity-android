package com.agy.ui.lifecycle;

import android.app.Activity;
import android.content.Intent;

import com.agy.oauth.OAuthManager;

/** Forwards auth deep links from intents to the OAuth handler. */
public final class DeepLinkForwarder {

    private DeepLinkForwarder() {
    }

    public static void forward(Activity activity, Intent intent, OAuthManager oauth,
                               Runnable onAuth) {
        if (oauth != null) {
            oauth.handleDeepLink(activity, intent, onAuth);
        }
    }
}
