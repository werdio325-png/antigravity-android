package dev.continuum.antigravity.runtime;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.util.Log;

/**
 * Handles flashlight (torch) control requests via broadcast `dev.continuum.antigravity.ACTION_TORCH`.
 * Allows chat agent / shell scripts to control the torch directly and reliably.
 */
public class TorchReceiver extends BroadcastReceiver {
    private static final String TAG = "TorchReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !"dev.continuum.antigravity.ACTION_TORCH".equals(intent.getAction())) {
            return;
        }
        boolean enable = intent.getBooleanExtra("enabled", true);
        if (intent.hasExtra("state")) {
            String state = intent.getStringExtra("state");
            if ("off".equalsIgnoreCase(state) || "0".equals(state) || "false".equalsIgnoreCase(state)) {
                enable = false;
            } else if ("on".equalsIgnoreCase(state) || "1".equals(state) || "true".equalsIgnoreCase(state)) {
                enable = true;
            }
        }
        setTorchMode(context, enable);
    }

    public static void setTorchMode(Context context, boolean enabled) {
        if (context == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                CameraManager camManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
                if (camManager != null) {
                    String[] ids = camManager.getCameraIdList();
                    for (String id : ids) {
                        try {
                            CameraCharacteristics chars = camManager.getCameraCharacteristics(id);
                            Boolean flashAvailable = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                            Integer facing = chars.get(CameraCharacteristics.LENS_FACING);
                            if (flashAvailable != null && flashAvailable) {
                                camManager.setTorchMode(id, enabled);
                                Log.i(TAG, "setTorchMode(" + id + ", " + enabled + ") succeeded (facing=" + facing + ")");
                                break;
                            }
                        } catch (Throwable t) {
                            Log.d(TAG, "Camera " + id + " check/set failed: " + t.getMessage());
                        }
                    }
                }
            } catch (Throwable e) {
                Log.w(TAG, "failed to toggle torch: " + e.getMessage());
            }
        }
    }
}
