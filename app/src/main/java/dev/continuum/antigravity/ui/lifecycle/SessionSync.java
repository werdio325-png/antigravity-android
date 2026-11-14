package dev.continuum.antigravity.ui.lifecycle;

import android.os.SystemClock;
import android.util.Log;
import android.webkit.WebView;

import org.json.JSONObject;

import dev.continuum.antigravity.BuildConfig;

/**
 * Tells the page how long it was in the background, so the web sync patch can
 * decide whether its live stream survived.
 *
 * The native layer never reloads the UI by itself here: it reports the facts
 * (background duration, whether the engine is up, versions) and lets
 * {@code web/src/patches/session_sync} reconcile only when the stream is gone.
 */
public final class SessionSync {

    private static final String TAG = "SessionSync";
    /** Below this, backgrounding is too short to need a resync nudge. */
    private static final long MIN_BACKGROUND_MS = 3000L;

    private long backgroundSince;

    public void onPause() {
        if (backgroundSince == 0L) {
            backgroundSince = SystemClock.elapsedRealtime();
        }
    }

    /**
     * @param engineReady whether the core is currently serving (foreground service
     *                    reported a live server URL)
     * @return background duration in ms, or -1 when there was nothing to report
     */
    public long onResume(WebView webView, boolean engineReady, String engineVersion) {
        if (backgroundSince == 0L || webView == null) {
            return -1L;
        }
        long backgroundMs = SystemClock.elapsedRealtime() - backgroundSince;
        backgroundSince = 0L;
        if (backgroundMs < MIN_BACKGROUND_MS) {
            return backgroundMs;
        }
        try {
            JSONObject payload = new JSONObject();
            payload.put("backgroundMs", backgroundMs);
            payload.put("engineAlive", engineReady);
            payload.put("appVersion", BuildConfig.APP_VERSION_NAME);
            payload.put("appVersionCode", BuildConfig.APP_VERSION_CODE);
            payload.put("engineVersion", engineVersion == null ? "unknown" : engineVersion);
            String script = "window.__agSessionSync && window.__agSessionSync.onResume("
                    + payload.toString() + ");";
            webView.evaluateJavascript(script, null);
            Log.i(TAG, "resume sync: backgroundMs=" + backgroundMs + " engineAlive=" + engineReady);
        } catch (Exception e) {
            Log.w(TAG, "cannot dispatch resume sync: " + e.getMessage());
        }
        return backgroundMs;
    }

    /** True when the app is currently considered backgrounded. */
    public boolean isBackgrounded() {
        return backgroundSince != 0L;
    }
}
