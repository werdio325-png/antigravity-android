package com.agy.bridge;

import android.util.Log;

import com.agy.ui.MainActivity;

/**
 * Routes UI-ready/error callbacks from JS to the host activity. addJavascriptInterface
 * callbacks land on a WebView thread, so hop to the main thread first.
 */
public final class UiBridge {

    private static final String TAG = "AndroidBridge";

    private volatile MainActivity activity;

    public void setActivity(MainActivity activity) {
        this.activity = activity;
    }

    public void onUiReady() {
        Log.i(TAG, "onUiReady() received from JavaScript; posting dismissSplash");
        final MainActivity host = this.activity;
        if (host == null) {
            return;
        }
        host.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                host.dismissSplash();
            }
        });
    }

    public void onUiError(final String errorMessage) {
        Log.e(TAG, "onUiError() received from JavaScript: " + errorMessage);
        final MainActivity host = this.activity;
        if (host == null) {
            return;
        }
        host.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                host.showFatalError(errorMessage);
            }
        });
    }
}
