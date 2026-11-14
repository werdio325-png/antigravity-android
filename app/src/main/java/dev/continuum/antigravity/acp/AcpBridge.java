package dev.continuum.antigravity.acp;

import android.content.Context;
import android.webkit.JavascriptInterface;

import org.json.JSONObject;

import java.nio.charset.Charset;
import java.security.MessageDigest;

import dev.continuum.antigravity.backend.BackendSettings;

/**
 * JavaScript surface for the agent backend, exposed as {@code AndroidAgent}.
 *
 * Read-only status is open; every action needs the native bridge token so a
 * third-party frame cannot drive the agent. Nothing here approves tools or signs
 * the user in silently.
 */
public final class AcpBridge {

    private final Context context;
    private final AcpRuntime runtime;
    private final String token;

    public AcpBridge(Context context, String nativeToken) {
        this.context = context.getApplicationContext();
        this.runtime = AcpRuntime.get(this.context);
        this.token = nativeToken;
    }

    @JavascriptInterface
    public String status() {
        JSONObject status = runtime.status();
        try {
            status.put("mode", BackendSettings.mode(context).name().toLowerCase());
        } catch (Exception ignored) {
        }
        return status.toString();
    }

    @JavascriptInterface
    public void setBackend(String token, String mode) {
        if (!allowed(token)) {
            return;
        }
        try {
            BackendSettings.setMode(context, BackendSettings.Mode.valueOf(mode.toUpperCase()));
            if (BackendSettings.mode(context) == BackendSettings.Mode.ACP) {
                runtime.ensureStarted(BackendSettings.acpWorkspace(context));
            } else {
                runtime.stop();
            }
        } catch (Exception ignored) {
        }
    }

    @JavascriptInterface
    public void installCli(String token) {
        if (!allowed(token)) {
            return;
        }
        // Runs on the runtime's worker via ensureStarted after installation; the
        // download itself is blocking, so keep it off the WebView thread.
        new Thread(new Runnable() {
            @Override
            public void run() {
                CliInstaller.install(context, false, null);
                runtime.restart();
            }
        }, "acp-cli-install").start();
    }

    @JavascriptInterface
    public void start(String token, String workspace) {
        if (allowed(token)) {
            runtime.ensureStarted(workspace);
        }
    }

    @JavascriptInterface
    public void restart(String token) {
        if (allowed(token)) {
            runtime.restart();
        }
    }

    @JavascriptInterface
    public void stop(String token) {
        if (allowed(token)) {
            runtime.stop();
        }
    }

    @JavascriptInterface
    public void prompt(String token, String text) {
        if (allowed(token) && text != null && !text.trim().isEmpty()) {
            runtime.sendPrompt(text);
        }
    }

    @JavascriptInterface
    public void cancel(String token) {
        if (allowed(token)) {
            runtime.cancel();
        }
    }

    @JavascriptInterface
    public void selectModel(String token, String modelId) {
        if (allowed(token) && modelId != null && !modelId.isEmpty()) {
            runtime.selectModel(modelId);
        }
    }

    @JavascriptInterface
    public void selectThinking(String token, String level) {
        if (allowed(token) && level != null && !level.isEmpty()) {
            runtime.selectThinking(level);
        }
    }

    @JavascriptInterface
    public void authenticate(String token, String methodId) {
        if (allowed(token) && methodId != null && !methodId.isEmpty()) {
            runtime.authenticate(methodId);
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
