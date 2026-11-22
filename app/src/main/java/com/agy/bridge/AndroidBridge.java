package com.agy.bridge;

import android.content.Context;
import android.webkit.JavascriptInterface;

import com.agy.core.VoiceForegroundService;
import com.agy.runtime.RuntimeManager;
import com.agy.shell.ShizukuBridge;
import com.agy.theme.ThemeManager;
import com.agy.ui.MainActivity;
import com.agy.ui.extra.ClipboardImageReader;
import com.agy.ui.extra.ExtraKeysBarView;
import com.agy.ui.extra.LocalPreviewDialog;

import java.lang.ref.WeakReference;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Minimal, low-privilege JavaScript bridge for the app UI. It is visible to
 * embedded frames, so shell, notification, and arbitrary URL capabilities must
 * not be exposed here. Native-only getters below are intentionally unannotated.
 */
public final class AndroidBridge {

    private static final String REMOTE_PORTAL_URL = "https://antigravity.google.com";

    private final Context appContext;
    private final UiBridge ui = new UiBridge();
    private final ThemeBridge themeBridge;
    private final EngineBridge engine;
    private final EngineConfigJson config;
    private final String blobBridgeToken = createBlobBridgeToken();
    private volatile WeakReference<MainActivity> activityRef;

    public AndroidBridge(Context context, RuntimeManager runtime,
                         ShizukuBridge shizuku, ThemeManager theme) {
        this.appContext = context.getApplicationContext();
        this.themeBridge = new ThemeBridge(theme);
        this.engine = new EngineBridge(runtime);
        this.config = new EngineConfigJson(this.appContext, runtime, engine, shizuku, themeBridge);
    }

    /** Host activity, used to dismiss the native splash once the UI has painted. */
    public void setActivity(MainActivity activity) {
        this.activityRef = activity != null ? new WeakReference<MainActivity>(activity) : null;
        ui.setActivity(activity);
    }

    @JavascriptInterface
    public void onUiReady(String token) {
        if (matchesNativeBridgeToken(token)) {
            ui.onUiReady();
            final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
            if (act != null) {
                act.runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        act.onWebUiReady();
                    }
                });
            }
        }
    }

    @JavascriptInterface
    public void openDirectoryPicker() {
        final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
        if (act != null) {
            act.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    act.launchDirectoryPicker();
                }
            });
        }
    }

    @JavascriptInterface
    public void onUiError(String token, final String errorMessage) {
        if (matchesNativeBridgeToken(token)) {
            ui.onUiError(errorMessage);
        }
    }

    @JavascriptInterface
    public boolean setTheme(String mode) {
        return themeBridge.setTheme(mode);
    }

    @JavascriptInterface
    public boolean setThemeByUser(final String mode) {
        return themeBridge.setThemeByUser(mode);
    }

    @JavascriptInterface
    public void onVisualThemeChanged(String visual) {
        themeBridge.onVisualThemeChanged(visual);
    }

    @JavascriptInterface
    public String getTheme() {
        return themeBridge.getTheme();
    }

    @JavascriptInterface
    public boolean isDesktopMode() {
        return ui.isDesktopMode();
    }

    @JavascriptInterface
    public void setDesktopMode(boolean enabled) {
        ui.setDesktopMode(enabled);
    }

    @JavascriptInterface
    public boolean isExtraKeysVisible() {
        return ExtraKeysBarView.isVisiblePref(appContext);
    }

    @JavascriptInterface
    public void setExtraKeysVisible(final boolean visible) {
        ExtraKeysBarView.setVisiblePref(appContext, visible);
        final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
        if (act != null) {
            act.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    act.setExtraKeysVisible(visible);
                }
            });
        }
    }

    /** Opens an isolated bridge-free native preview dialog for a loopback HTTP/HTTPS URL. */
    @JavascriptInterface
    public boolean openLocalPreview(String token, final String url) {
        if (!matchesNativeBridgeToken(token)) {
            return false;
        }
        if (!LocalPreviewDialog.isAllowedLoopbackUrl(url)) {
            return false;
        }
        final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
        if (act == null) {
            return false;
        }
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                act.openLocalPreview(url);
            }
        });
        return true;
    }

    /**
     * Opens the official Antigravity Remote Control portal (https://antigravity.google.com)
     * in an in-app Chrome Custom Tab (with system browser fallback) so the user can drive
     * their PC desktop/CLI sessions and Remote Agents using their existing Google session
     * while keeping AndroidBridge strictly isolated to TrustedCoreOrigin.
     */
    @JavascriptInterface
    public boolean openRemotePortal(String token) {
        if (!matchesNativeBridgeToken(token)) {
            return false;
        }
        final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
        if (act != null) {
            act.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    act.openRemotePortal(REMOTE_PORTAL_URL);
                }
            });
            return true;
        }
        return new com.agy.oauth.CustomTabLauncher().openInBrowser(appContext, REMOTE_PORTAL_URL);
    }

    @JavascriptInterface
    public boolean startVoiceDictation(String token) {
        if (!matchesNativeBridgeToken(token)) {
            return false;
        }
        final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
        if (act == null) {
            return false;
        }
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                act.startVoiceDictation();
            }
        });
        return true;
    }

    @JavascriptInterface
    public boolean stopVoiceDictation(String token) {
        if (!matchesNativeBridgeToken(token)) {
            return false;
        }
        final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
        if (act == null) {
            return false;
        }
        act.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                act.stopVoiceDictation();
            }
        });
        return true;
    }

    @JavascriptInterface
    public boolean isVoiceDictating() {
        final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
        return act != null && act.isVoiceDictating();
    }

    @JavascriptInterface
    public boolean startVoiceService(String token) {
        if (!matchesNativeBridgeToken(token)) {
            return false;
        }
        VoiceForegroundService.start(appContext);
        return true;
    }

    @JavascriptInterface
    public boolean stopVoiceService(String token) {
        if (!matchesNativeBridgeToken(token)) {
            return false;
        }
        VoiceForegroundService.stop(appContext);
        return true;
    }

    @JavascriptInterface
    public boolean setVoiceMuted(String token, boolean muted) {
        if (!matchesNativeBridgeToken(token)) {
            return false;
        }
        VoiceForegroundService.setMuted(appContext, muted);
        return true;
    }

    @JavascriptInterface
    public boolean isVoiceServiceActive() {
        return VoiceForegroundService.isActive();
    }

    @JavascriptInterface
    public boolean isVoiceMuted() {
        return VoiceForegroundService.isMuted();
    }

    @JavascriptInterface
    public boolean hasClipboardImage() {
        return ClipboardImageReader.hasClipboardImage(appContext);
    }

    /**
     * Reads the current Android clipboard image (content:// or safe file://, max 12 MiB)
     * as JSON: {"ok":true,"mime":"...","name":"...","base64":"..."} or {"ok":false,"error":"..."}.
     */
    @JavascriptInterface
    public String readClipboardImage(String token) {
        if (!matchesNativeBridgeToken(token)) {
            return "{\"ok\":false,\"error\":\"unauthorized\"}";
        }
        return ClipboardImageReader.readClipboardImageJson(appContext);
    }

    // Native-only helpers used while preparing the trusted app document.
    public boolean isEngineReady() {
        return engine.isReady();
    }

    public int getEnginePort() {
        return engine.getPort();
    }

    public String getEngineBaseUrl() {
        return engine.getBaseUrl();
    }

    public String getCsrfToken() {
        return engine.getCsrfToken();
    }

    public String getConfig() {
        return config.get();
    }

    /** Native-only token inserted into the trusted top-level document. */
    public String getBlobBridgeToken() {
        return blobBridgeToken;
    }

    /** Saves a downloaded blob only for scripts holding the top-level document token. */
    @JavascriptInterface
    public void saveBlob(String token, String base64Data, String filename, String mimeType) {
        if (!matchesNativeBridgeToken(token)) {
            return;
        }
        final MainActivity act = activityRef != null ? activityRef.get() : MainActivity.activeActivity;
        if (act != null) {
            com.agy.ui.download.FileDownloader.handleBlobData(
                    act, base64Data, filename, mimeType);
        }
    }

    private boolean matchesNativeBridgeToken(String candidate) {
        if (candidate == null) {
            return false;
        }
        try {
            byte[] expected = blobBridgeToken.getBytes(Charset.forName("UTF-8"));
            byte[] actual = candidate.getBytes(Charset.forName("UTF-8"));
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String createBlobBridgeToken() {
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        char[] hex = "0123456789abcdef".toCharArray();
        char[] encoded = new char[random.length * 2];
        for (int i = 0; i < random.length; i++) {
            int value = random[i] & 0xff;
            encoded[i * 2] = hex[value >>> 4];
            encoded[i * 2 + 1] = hex[value & 0x0f];
        }
        return new String(encoded);
    }
}
