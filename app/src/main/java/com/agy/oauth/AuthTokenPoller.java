package com.agy.oauth;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

/**
 * Bounded poll for the token file the core writes asynchronously after its
 * loopback callback. Without it the UI would reload and re-render the login.
 */
public final class AuthTokenPoller {

    private static final String TAG = "MainActivity";

    /** Bounded wait for the core to persist the token after the deep link. */
    public static final long AUTH_TOKEN_POLL_MS = 400;
    public static final int AUTH_TOKEN_MAX_ATTEMPTS = 75;

    public interface Callback {
        /** Token file observed; safe to reload the app. */
        void onTokenReady(int attempts);

        /** Gave up waiting; the current UI should stay. */
        void onTimeout();
    }

    private final Context context;
    private final Handler handler;
    private final Callback callback;
    private boolean active;

    public AuthTokenPoller(Context context, Handler handler, Callback callback) {
        this.context = context;
        this.handler = handler;
        this.callback = callback;
    }

    public boolean isActive() {
        return active;
    }

    /** Starts a single poll cycle; a second call while active is a no-op. */
    public void start() {
        if (active) {
            return;
        }
        active = true;
        poll(0);
    }

    private void poll(final int attempt) {
        if (OAuthTokenStore.hasToken(context)) {
            active = false;
            Log.i(TAG, "auth token present after " + attempt + " poll(s); reloading UI");
            callback.onTokenReady(attempt);
            return;
        }
        if (attempt >= AUTH_TOKEN_MAX_ATTEMPTS) {
            active = false;
            Log.w(TAG, "auth token not found after "
                    + (AUTH_TOKEN_MAX_ATTEMPTS * AUTH_TOKEN_POLL_MS / 1000) + "s; keeping current UI");
            callback.onTimeout();
            return;
        }
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                poll(attempt + 1);
            }
        }, AUTH_TOKEN_POLL_MS);
    }
}
