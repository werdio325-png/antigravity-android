package com.agy.ui.lifecycle;

import android.content.ComponentCallbacks2;
import android.os.Handler;
import android.webkit.WebView;

import com.agy.ui.webview.WebViewHost;
import com.agy.ui.webview.WebViewLifecycle;

/** Activity lifecycle glue: WebView resume, trim-memory and handler teardown. */
public final class AppLifecycle {

    private AppLifecycle() {
    }

    public static void resume(WebView webView) {
        WebViewLifecycle.resume(webView);
    }

    public static void pauseTimers(WebView webView) {
        WebViewLifecycle.resumeTimers(webView);
    }

    public static void trimMemory(int level, Handler handler, WebViewHost host) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE && host != null) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    host.freeMemory();
                }
            });
        }
    }

    public static void destroy(Handler handler) {
        handler.removeCallbacksAndMessages(null);
    }
}
