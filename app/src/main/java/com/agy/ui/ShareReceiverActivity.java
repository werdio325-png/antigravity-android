package com.agy.ui;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;

import com.agy.ui.extra.SharePayloadExtractor;
import com.agy.ui.launcher.LauncherDarkActivity;
import com.agy.ui.launcher.LauncherLightActivity;

/**
 * Dedicated exported Share Sheet target (ACTION_SEND / ACTION_SEND_MULTIPLE).
 * Extracts shared text/subject/files into cacheDir/shared/ while URI permissions
 * are active, then forwards the JSON payload to the active host activity without
 * disturbing dynamic LauncherDarkActivity/LauncherLightActivity switching.
 */
public final class ShareReceiverActivity extends Activity {

    private static final String TAG = "ShareReceiverActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        final Intent incoming = getIntent();
        if (!SharePayloadExtractor.isShareIntent(incoming)) {
            finish();
            return;
        }

        if (!SharePayloadExtractor.hasStreamUris(incoming)) {
            String payloadJson = SharePayloadExtractor.extractToJson(this, incoming);
            forwardAndFinish(payloadJson);
            return;
        }

        // Copy content:// streams off the UI thread while this activity remains
        // alive so temporary FLAG_GRANT_READ_URI_PERMISSION grants stay valid.
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String payloadJson = SharePayloadExtractor.extractToJson(
                        ShareReceiverActivity.this, incoming);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        forwardAndFinish(payloadJson);
                    }
                });
            }
        }, "agy-share-extract").start();
    }

    private void forwardAndFinish(String payloadJson) {
        if (payloadJson != null && !payloadJson.isEmpty()) {
            Class<?> targetClass = resolveHostActivityClass();
            Intent launch = new Intent(this, targetClass);
            launch.setAction(Intent.ACTION_MAIN);
            launch.putExtra(MainActivity.EXTRA_SHARED_PAYLOAD_JSON, payloadJson);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            try {
                startActivity(launch);
            } catch (Exception e) {
                Log.w(TAG, "failed launching " + targetClass.getName() + ", falling back to MainActivity", e);
                try {
                    Intent fallback = new Intent(this, MainActivity.class);
                    fallback.putExtra(MainActivity.EXTRA_SHARED_PAYLOAD_JSON, payloadJson);
                    fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                    startActivity(fallback);
                } catch (Exception ex) {
                    Log.e(TAG, "failed launching MainActivity fallback", ex);
                }
            }
        }
        finish();
    }

    private Class<?> resolveHostActivityClass() {
        MainActivity active = MainActivity.activeActivity;
        if (active != null && !active.isFinishing() && !active.isDestroyed()) {
            return active.getClass();
        }
        try {
            PackageManager pm = getPackageManager();
            int lightState = pm.getComponentEnabledSetting(
                    new ComponentName(this, LauncherLightActivity.class));
            if (lightState == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                return LauncherLightActivity.class;
            }
            int darkState = pm.getComponentEnabledSetting(
                    new ComponentName(this, LauncherDarkActivity.class));
            if (darkState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    && darkState != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
                return LauncherDarkActivity.class;
            }
        } catch (Exception ignored) {
        }
        return MainActivity.class;
    }
}
