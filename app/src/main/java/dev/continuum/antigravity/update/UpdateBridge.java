package dev.continuum.antigravity.update;

import android.webkit.JavascriptInterface;

import java.nio.charset.Charset;
import java.security.MessageDigest;

/**
 * JavaScript surface for the in-app updater, exposed as {@code AndroidUpdate}.
 *
 * Mutating calls require the same native bridge token that is injected into the
 * trusted top-level document, so embedded third-party frames cannot trigger
 * downloads. Installation always goes through Android's package installer.
 */
public final class UpdateBridge {

    private final UpdateManager manager;
    private final String token;

    public UpdateBridge(UpdateManager manager, String nativeToken) {
        this.manager = manager;
        this.token = nativeToken;
    }

    @JavascriptInterface
    public String getState() {
        return manager.status().toString();
    }

    @JavascriptInterface
    public String getInstalledVersion() {
        return manager.installedVersion();
    }

    @JavascriptInterface
    public void check(String token) {
        if (allowed(token)) {
            manager.check();
        }
    }

    @JavascriptInterface
    public void download(String token) {
        if (allowed(token)) {
            manager.download();
        }
    }

    @JavascriptInterface
    public void install(String token) {
        if (allowed(token)) {
            manager.install();
        }
    }

    @JavascriptInterface
    public void postpone(String token) {
        if (allowed(token)) {
            manager.postpone();
        }
    }

    private boolean allowed(String candidate) {
        if (candidate == null || token == null) {
            return false;
        }
        try {
            return MessageDigest.isEqual(token.getBytes(Charset.forName("UTF-8")),
                    candidate.getBytes(Charset.forName("UTF-8")));
        } catch (Exception e) {
            return false;
        }
    }
}
