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

    /** Binds the retry and log export bridge to the splash WebView. */
    public static void bind(WebView splashView, android.content.Context context, SplashRetryListener listener) {
        if (splashView == null) {
            return;
        }
        splashView.addJavascriptInterface(new Bridge(context, listener), RETRY_BRIDGE);
    }

    public static void bind(WebView splashView, SplashRetryListener listener) {
        bind(splashView, null, listener);
    }

    private static final class Bridge {
        private final android.content.Context context;
        private final SplashRetryListener listener;

        Bridge(android.content.Context context, SplashRetryListener listener) {
            this.context = context != null ? context.getApplicationContext() : null;
            this.listener = listener;
        }

        @JavascriptInterface
        public void retry() {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (listener != null) {
                            listener.onRetry();
                        }
                    } catch (Exception ignored) {
                    }
                }
            });
        }

        @JavascriptInterface
        public void copyLogs() {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    LogExporter.copyToClipboard(context);
                }
            });
        }

        @JavascriptInterface
        public void saveLogs() {
            MAIN.post(new Runnable() {
                @Override
                public void run() {
                    LogExporter.saveAndShare(context);
                }
            });
        }
    }
}
