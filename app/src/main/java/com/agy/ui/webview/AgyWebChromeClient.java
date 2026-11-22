package com.agy.ui.webview;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Message;
import android.util.Log;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import com.agy.bridge.AndroidBridge;
import com.agy.util.SensitiveDataRedactor;
import com.agy.util.TrustedCoreOrigin;

/**
 * Routes console output to logcat, popups to PopupWindowHandler, file chooser
 * to callback, and trusted-origin audio capture permission requests to the host.
 */
public final class AgyWebChromeClient extends WebChromeClient {

    private static final String TAG = "WebViewHost";

    public interface FileChooserCallback {
        boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                  FileChooserParams fileChooserParams);
    }

    public interface AudioPermissionCallback {
        void requestRecordAudioPermission();
    }

    private final Context context;
    private final AndroidBridge bridge;
    private final PopupWindowHandler popupHandler;
    private final FileChooserCallback fileChooserCallback;
    private final AudioPermissionCallback audioPermissionCallback;
    private PermissionRequest pendingAudioPermissionRequest;

    public AgyWebChromeClient(Context context, AndroidBridge bridge,
                              PopupWindowHandler popupHandler,
                              FileChooserCallback fileChooserCallback,
                              AudioPermissionCallback audioPermissionCallback) {
        this.context = context != null ? context.getApplicationContext() : null;
        this.bridge = bridge;
        this.popupHandler = popupHandler;
        this.fileChooserCallback = fileChooserCallback;
        this.audioPermissionCallback = audioPermissionCallback;
    }

    public AgyWebChromeClient(PopupWindowHandler popupHandler, FileChooserCallback fileChooserCallback) {
        this(null, null, popupHandler, fileChooserCallback, null);
    }

    public AgyWebChromeClient(PopupWindowHandler popupHandler) {
        this(null, null, popupHandler, null, null);
    }

    @Override
    public boolean onConsoleMessage(ConsoleMessage cm) {
        if (cm != null) {
            String msg = SensitiveDataRedactor.redactLine(cm.message());
            String src = SensitiveDataRedactor.safeUrl(cm.sourceId());
            int line = cm.lineNumber();
            ConsoleMessage.MessageLevel lvl = cm.messageLevel();
            String tag = "AGY_JS";
            if (lvl == ConsoleMessage.MessageLevel.ERROR) {
                Log.e(tag, "[" + src + ":" + line + "] " + msg);
            } else if (lvl == ConsoleMessage.MessageLevel.WARNING) {
                Log.w(tag, "[" + src + ":" + line + "] " + msg);
            } else {
                Log.i(tag, "[" + src + ":" + line + "] " + msg);
            }
        }
        return true;
    }

    @Override
    public boolean onCreateWindow(WebView parent, boolean isDialog, boolean isUserGesture,
                                  Message resultMsg) {
        return popupHandler.handle(parent, isDialog, isUserGesture, resultMsg);
    }

    @Override
    public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback,
                                      FileChooserParams fileChooserParams) {
        if (fileChooserCallback != null) {
            return fileChooserCallback.onShowFileChooser(webView, filePathCallback, fileChooserParams);
        }
        return super.onShowFileChooser(webView, filePathCallback, fileChooserParams);
    }

    @Override
    public void onPermissionRequest(PermissionRequest request) {
        if (request == null) {
            return;
        }
        Uri origin = request.getOrigin();
        if (bridge == null || !TrustedCoreOrigin.matches(origin, bridge.getEnginePort())) {
            Log.w(TAG, "denying WebView permission request from untrusted origin: "
                    + SensitiveDataRedactor.safeUrl(origin != null ? origin.toString() : null));
            request.deny();
            return;
        }
        if (!requestsAudioCapture(request.getResources())) {
            Log.w(TAG, "denying non-audio WebView permission request");
            request.deny();
            return;
        }
        if (hasRecordAudioPermission()) {
            Log.i(TAG, "granting WebView RESOURCE_AUDIO_CAPTURE for trusted core origin");
            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            return;
        }
        if (audioPermissionCallback == null) {
            Log.w(TAG, "denying WebView audio capture: no runtime permission callback");
            request.deny();
            return;
        }
        if (pendingAudioPermissionRequest != null && pendingAudioPermissionRequest != request) {
            try {
                pendingAudioPermissionRequest.deny();
            } catch (Throwable ignored) {
            }
        }
        pendingAudioPermissionRequest = request;
        Log.i(TAG, "holding WebView audio permission request until runtime RECORD_AUDIO result");
        audioPermissionCallback.requestRecordAudioPermission();
    }

    @Override
    public void onPermissionRequestCanceled(PermissionRequest request) {
        if (request != null && request == pendingAudioPermissionRequest) {
            pendingAudioPermissionRequest = null;
        }
    }

    public void onRecordAudioPermissionResult(boolean granted) {
        PermissionRequest pending = pendingAudioPermissionRequest;
        pendingAudioPermissionRequest = null;
        if (pending == null) {
            return;
        }
        try {
            if (granted) {
                Log.i(TAG, "runtime RECORD_AUDIO granted; granting pending WebView audio capture");
                pending.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                Log.w(TAG, "runtime RECORD_AUDIO denied; denying pending WebView audio capture");
                pending.deny();
            }
        } catch (Throwable t) {
            Log.w(TAG, "failed resolving pending WebView audio permission request", t);
        }
    }

    public void cancelPendingPermissions() {
        PermissionRequest pending = pendingAudioPermissionRequest;
        pendingAudioPermissionRequest = null;
        if (pending != null) {
            try {
                pending.deny();
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean hasRecordAudioPermission() {
        if (context == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    private static boolean requestsAudioCapture(String[] resources) {
        if (resources == null) {
            return false;
        }
        for (String res : resources) {
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(res)) {
                return true;
            }
        }
        return false;
    }
}
