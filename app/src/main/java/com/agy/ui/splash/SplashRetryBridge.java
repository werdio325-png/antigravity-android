package com.agy.ui.splash;

import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

/** JS bridge named SplashRetry; its {@code retry()} calls back on the main thread. */
public final class SplashRetryBridge {

    public static final String RETRY_BRIDGE = "SplashRetry";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private SplashRetryBridge() {
    }

    /** Binds the retry bridge to the splash WebView. Call once per splash view. */
    public static void bind(WebView splashView, SplashRetryListener listener) {
        if (splashView == null || listener == null) {
            return;
        }
        splashView.addJavascriptInterface(new Bridge(listener), RETRY_BRIDGE);
    }

    private static final class Bridge {
        private final SplashRetryListener listener;

        Bridge(SplashRetryListener listener) {
            this.listener = listener;
        }

        @JavascriptInterface
        public void retry() {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        listener.onRetry();
                    } catch (Exception ignored) {
                    }
                }
            });
        }
    }
}
