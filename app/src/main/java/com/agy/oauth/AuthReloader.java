package com.agy.oauth;

import android.os.Handler;
import android.webkit.WebView;

/** Notifies a live UI of a successful auth, then reloads the app after a delay. */
public final class AuthReloader {

    /** Delay before the reload so the current UI can process the auth event. */
    public static final long RELOAD_DELAY_MS = 600;

    private AuthReloader() {
    }

    /** Dispatches the auth-success events to the currently loaded UI. */
    public static void dispatchAuthSuccess(WebView webView) {
        if (webView == null) {
            return;
        }
        webView.evaluateJavascript(
                "(function(){"
                        + "window.dispatchEvent(new CustomEvent('auth-success'));"
                        + "window.dispatchEvent(new MessageEvent('message', {data: {type: 'AUTH_SUCCESS'}}));"
                        + "if (window.broadcastChannel) { try { window.broadcastChannel.postMessage({type: 'AUTH_SUCCESS'}); } catch(e){} }"
                        + "})();", null);
    }

    /** Posts the reload action after {@link #RELOAD_DELAY_MS}. */
    public static void scheduleReload(Handler handler, final Runnable reloadAction) {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                reloadAction.run();
            }
        }, RELOAD_DELAY_MS);
    }
}
