package dev.continuum.antigravity.bridge;

import android.util.Log;

import java.lang.ref.WeakReference;

import dev.continuum.antigravity.ui.MainActivity;
import dev.continuum.antigravity.util.SensitiveDataRedactor;

/**
 * Routes UI-ready/error callbacks from JS to the host activity. addJavascriptInterface
 * callbacks land on a WebView thread, so hop to the main thread first.
 */
public final class UiBridge {

    private static final String TAG = "AndroidBridge";

    private volatile WeakReference<MainActivity> activityRef;

    public void setActivity(MainActivity activity) {
        this.activityRef = activity != null ? new WeakReference<MainActivity>(activity) : null;
    }

    public void onUiReady() {
        Log.i(TAG, "onUiReady() received from JavaScript; posting dismissSplash");
        final MainActivity host = this.activityRef != null ? this.activityRef.get() : null;
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
        final String safeError = SensitiveDataRedactor.redactLine(errorMessage);
        Log.e(TAG, "onUiError() received from JavaScript: " + safeError);
        final MainActivity host = this.activityRef != null ? this.activityRef.get() : null;
        if (host == null) {
            return;
        }
        host.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                host.showFatalError(safeError);
            }
        });
    }

    public boolean isDesktopMode() {
        final MainActivity host = this.activityRef != null ? this.activityRef.get() : null;
        return host != null ? host.isDesktopMode() : false;
    }

    public void setDesktopMode(final boolean enabled) {
        final MainActivity host = this.activityRef != null ? this.activityRef.get() : null;
        if (host == null) {
            return;
        }
        host.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                host.setDesktopMode(enabled);
            }
        });
    }
}
