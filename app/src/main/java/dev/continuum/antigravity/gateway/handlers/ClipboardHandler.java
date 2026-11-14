package dev.continuum.antigravity.gateway.handlers;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Thread-safe handler for Android clipboard read and write operations.
 */
public final class ClipboardHandler {

    private static final String TAG = "ClipboardHandler";
    private final Context context;
    private final Handler mainHandler;

    public ClipboardHandler(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public JSONObject handle(JSONObject payload) {
        JSONObject resp = new JSONObject();
        try {
            String action = payload.optString("action", "set");

            if ("get".equalsIgnoreCase(action)) {
                AtomicReference<String> clipText = new AtomicReference<>("");
                CountDownLatch latch = new CountDownLatch(1);
                mainHandler.post(() -> {
                    try {
                        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null && cm.hasPrimaryClip()) {
                            ClipData clip = cm.getPrimaryClip();
                            if (clip != null && clip.getItemCount() > 0) {
                                CharSequence text = clip.getItemAt(0).getText();
                                if (text != null) {
                                    clipText.set(text.toString());
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "get clipboard failed: " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
                latch.await(1500, TimeUnit.MILLISECONDS);
                resp.put("ok", true);
                resp.put("text", clipText.get());
                return resp;
            }

            // Set
            String text = payload.optString("text", "");
            mainHandler.post(() -> {
                try {
                    ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        ClipData clip = ClipData.newPlainText("antigravity", text);
                        cm.setPrimaryClip(clip);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "set clipboard failed: " + e.getMessage());
                }
            });
            resp.put("ok", true);
            resp.put("length", text.length());
        } catch (Exception e) {
            Log.w(TAG, "clipboard error: " + e.getMessage());
            try {
                resp.put("ok", false);
                resp.put("error", e.getMessage());
            } catch (Exception ignored) {}
        }
        return resp;
    }
}
