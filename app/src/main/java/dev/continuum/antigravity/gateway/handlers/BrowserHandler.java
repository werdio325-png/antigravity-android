package dev.continuum.antigravity.gateway.handlers;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

/**
 * Dispatches URLs to Chrome Custom Tabs or external system browsers.
 */
public final class BrowserHandler {

    private static final String TAG = "BrowserHandler";
    private final Context context;

    public BrowserHandler(Context context) {
        this.context = context.getApplicationContext();
    }

    public JSONObject handle(JSONObject payload) {
        JSONObject resp = new JSONObject();
        try {
            String url = payload.optString("url", "").trim();
            if (url.isEmpty()) {
                resp.put("ok", false);
                resp.put("error", "missing or empty 'url'");
                return resp;
            }

            boolean ok = dev.continuum.antigravity.util.UriDispatcher.open(context, url);
            resp.put("ok", ok);
            resp.put("url", url);
        } catch (Exception e) {
            Log.w(TAG, "failed to open url: " + e.getMessage());
            try {
                resp.put("ok", false);
                resp.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return resp;
    }
}
