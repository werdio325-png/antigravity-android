package dev.continuum.antigravity.acp;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.continuum.antigravity.BuildConfig;
import dev.continuum.antigravity.backend.BackendSettings;

/**
 * Runs Google's official Antigravity CLI as a managed Android process and speaks
 * ACP to it over stdio.
 *
 * One process at a time, started only when the ACP backend is selected. The CLI
 * must already be installed and signed in (Google's own flow); this class never
 * forges credentials, never approves tools on its own, and never restarts a turn
 * behind the user's back.
 */
public final class AcpRuntime {

    private static final String TAG = "AcpRuntime";
    private static final long INIT_TIMEOUT_MS = 20000L;

    public interface Sink {
        void emit(String javascript);
    }

    private static AcpRuntime instance;

    public static synchronized AcpRuntime get(Context context) {
        if (instance == null) {
            instance = new AcpRuntime(context.getApplicationContext());
        }
        return instance;
    }

    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean turnInFlight = new AtomicBoolean(false);
    private final AcpModelCatalog catalog = new AcpModelCatalog();

    private volatile Sink sink;
    private volatile Process process;
    private volatile AcpClient client;
    private volatile String lastError;
    private volatile boolean authRequired;
    private volatile JSONArray authMethods;
    private volatile String lastSyncAt;
    private volatile String cliVersion;

    private AcpRuntime(Context context) {
        this.context = context;
    }

    public void setSink(Sink sink) {
        this.sink = sink;
    }

    private void emit(JSONObject payload) {
        Sink current = sink;
        if (current == null) {
            return;
        }
        current.emit("window.__agAcpEvent && window.__agAcpEvent(" + payload.toString() + ");");
    }

    public synchronized JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            o.put("backend", BackendSettings.mode(context).name().toLowerCase());
            File binary = CliInstaller.binaryPath(context);
            o.put("cliInstalled", binary.isFile());
            o.put("cliVersion", cliVersion != null ? cliVersion : CliInstaller.installedVersion(context));
            o.put("processAlive", isProcessAlive());
            o.put("connected", client != null && !client.peer().isClosed());
            o.put("sessionId", BackendSettings.acpSessionId(context));
            o.put("workspace", BackendSettings.acpWorkspace(context));
            o.put("model", BackendSettings.acpModel(context));
            o.put("authRequired", authRequired);
            o.put("authMethods", authMethods != null ? authMethods : new JSONArray());
            o.put("thinking", catalog.toJson().opt("currentThinking"));
            o.put("catalogue", catalog.toJson());
            o.put("lastSyncAt", lastSyncAt);
            o.put("error", lastError);
            o.put("protocol", AcpClient.PROTOCOL_VERSION);
            o.put("appVersion", BuildConfig.APP_VERSION_NAME);
        } catch (Exception ignored) {
        }
        return o;
    }

    private boolean isProcessAlive() {
        Process current = process;
        if (current == null) {
            return false;
        }
        try {
            current.exitValue();
            return false;
        } catch (IllegalThreadStateException running) {
            return true;
        }
    }

    /** Starts the CLI and completes ACP initialization. Safe to call repeatedly. */
    public void ensureStarted(final String workspace) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ensureStartedBlocking(workspace);
                } catch (Exception e) {
                    fail(e);
                }
            }
        });
    }

    private synchronized void ensureStartedBlocking(String workspace) throws Exception {
        if (isProcessAlive() && client != null && !client.peer().isClosed()) {
            return;
        }
        File binary = CliInstaller.binaryPath(context);
        if (!binary.isFile()) {
            lastError = "Antigravity CLI is not installed. Install it from Settings first.";
            emitStatus();
            return;
        }
        File workDir = workspace != null ? new File(workspace) : new File(context.getFilesDir(), "projects");
        if (!workDir.isDirectory() && !workDir.mkdirs()) {
            throw new java.io.IOException("cannot create workspace directory");
        }
        File logDir = new File(context.getFilesDir(), "logs");
        if (!logDir.isDirectory()) {
            logDir.mkdirs();
        }

        List<String> command = new ArrayList<>();
        command.add(binary.getAbsolutePath());
        command.add("acp");
        command.add("--log-file");
        command.add(new File(logDir, "acp.log").getAbsolutePath());
        // Sandboxed where the device allows it; never skip permissions for the user.
        command.add("--sandbox");

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(workDir);
        // HOME is the app sandbox so the CLI's credentials and settings live with
        // this app and cannot leak into another installation.
        builder.environment().put("HOME", context.getFilesDir().getAbsolutePath());
        builder.environment().put("PATH", binary.getParentFile().getAbsolutePath()
                + ":" + context.getFilesDir().getAbsolutePath() + "/usr/bin");
        builder.environment().put("LANG", "C.UTF-8");
        builder.environment().put("TERM", "dumb");
        Process started = builder.start();
        this.process = started;

        AcpClient newClient = new AcpClient(new AcpClient.EventListener() {
            @Override
            public void onSessionUpdate(String sessionId, JSONObject update) {
                forwardUpdate(sessionId, update);
            }

            @Override
            public boolean onPermissionRequest(String sessionId, JSONObject params) {
                // No silent approvals: the UI must answer. Until then, deny.
                JSONObject event = new JSONObject();
                try {
                    event.put("type", "permission");
                    event.put("sessionId", sessionId);
                    event.put("request", params);
                } catch (Exception ignored) {
                }
                emit(event);
                lastError = "A tool permission request needs your confirmation in the CLI.";
                return false;
            }

            @Override
            public void onDisconnected(String reason) {
                lastError = "ACP connection closed: " + reason;
                emitStatus();
            }
        });
        newClient.connect(started.getInputStream(), started.getOutputStream());
        this.client = newClient;

        JSONObject init = newClient.initialize("Continuum", BuildConfig.APP_VERSION_NAME);
        JSONArray methods = init.optJSONArray("authMethods");
        this.authMethods = methods != null ? methods : new JSONArray();
        JSONObject agentInfo = init.optJSONObject("agentInfo");
        if (agentInfo != null) {
            this.cliVersion = agentInfo.optString("version", this.cliVersion);
        }

        String savedSession = BackendSettings.acpSessionId(context);
        try {
            JSONObject sessionResult;
            if (savedSession != null && isSessionLoadSupported(init)) {
                sessionResult = newClient.loadSession(savedSession, workDir.getAbsolutePath());
            } else {
                sessionResult = newClient.newSession(workDir.getAbsolutePath(), null);
            }
            catalog.applySessionResult(sessionResult);
            BackendSettings.setAcpSessionId(context, newClient.currentSessionId());
            BackendSettings.setAcpWorkspace(context, workDir.getAbsolutePath());
            authRequired = false;
            lastSyncAt = String.valueOf(System.currentTimeMillis());
        } catch (JsonRpcPeer.RpcException e) {
            if (e.isAuthenticationRequired()) {
                authRequired = true;
                lastError = "Sign in with Google: run the CLI's sign-in once, then reconnect.";
            } else {
                throw e;
            }
        }
        lastError = authRequired ? lastError : null;
        emitStatus();
    }

    private static boolean isSessionLoadSupported(JSONObject init) {
        JSONObject capabilities = init.optJSONObject("agentCapabilities");
        return capabilities != null && capabilities.optBoolean("loadSession", false);
    }

    private void forwardUpdate(String sessionId, JSONObject update) {
        JSONObject event = new JSONObject();
        try {
            event.put("type", "update");
            event.put("sessionId", sessionId);
            event.put("update", update);
            String kind = update.optString("sessionUpdate");
            if ("tool_call".equals(kind) || "tool_call_update".equals(kind)
                    || "plan".equals(kind) || "agent_message_chunk".equals(kind)
                    || "agent_thought_chunk".equals(kind)) {
                lastSyncAt = String.valueOf(System.currentTimeMillis());
            }
        } catch (Exception ignored) {
        }
        emit(event);
    }

    /** Sends one user turn. Never re-sends a turn after a failure. */
    public void sendPrompt(final String text) {
        if (!turnInFlight.compareAndSet(false, true)) {
            lastError = "A turn is already running.";
            emitStatus();
            return;
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ensureStartedBlocking(BackendSettings.acpWorkspace(context));
                    if (client == null || authRequired) {
                        throw new JsonRpcPeer.RpcException(-32000,
                                authRequired ? "Authentication required" : "agent is not running");
                    }
                    String model = BackendSettings.acpModel(context);
                    JSONObject result = client.prompt(text);
                    if (model != null) {
                        catalog.markExercised(model);
                    }
                    JSONObject event = new JSONObject();
                    try {
                        event.put("type", "turn");
                        event.put("stopReason", result.optString("stopReason", "end_turn"));
                        event.put("catalogue", catalog.toJson());
                    } catch (Exception ignored) {
                    }
                    emit(event);
                } catch (Exception e) {
                    handleTurnFailure(e);
                } finally {
                    turnInFlight.set(false);
                    emitStatus();
                }
            }
        });
    }

    private void handleTurnFailure(Exception e) {
        int code = -32603;
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        boolean login = false;
        if (e instanceof JsonRpcPeer.RpcException) {
            code = ((JsonRpcPeer.RpcException) e).code();
            login = ((JsonRpcPeer.RpcException) e).isAuthenticationRequired();
        }
        if (login) {
            authRequired = true;
        }
        String model = BackendSettings.acpModel(context);
        if (model != null) {
            catalog.markFailure(model, code, message, login);
        }
        lastError = message;
        JSONObject event = new JSONObject();
        try {
            event.put("type", "error");
            event.put("code", code);
            event.put("message", message);
            event.put("model", model == null ? JSONObject.NULL : model);
            event.put("catalogue", catalog.toJson());
        } catch (Exception ignored) {
        }
        emit(event);
    }

    public void cancel() {
        AcpClient current = client;
        if (current != null) {
            current.cancel();
            emitStatus();
        }
    }

    public void selectModel(final String modelId) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ensureStartedBlocking(BackendSettings.acpWorkspace(context));
                    if (client == null) {
                        throw new JsonRpcPeer.RpcException(-32002, "agent is not running");
                    }
                    String optionId = catalog.modelOptionId();
                    if (optionId == null) {
                        throw new JsonRpcPeer.RpcException(-32602, "agent exposes no model option");
                    }
                    client.setConfigOption(optionId, modelId);
                    BackendSettings.setAcpModel(context, modelId);
                    lastError = null;
                } catch (Exception e) {
                    lastError = e.getMessage();
                }
                emitStatus();
            }
        });
    }

    public void selectThinking(final String level) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ensureStartedBlocking(BackendSettings.acpWorkspace(context));
                    String optionId = catalog.thinkingOptionId();
                    if (client == null || optionId == null) {
                        throw new JsonRpcPeer.RpcException(-32602, "agent exposes no thinking option");
                    }
                    client.setConfigOption(optionId, level);
                    lastError = null;
                } catch (Exception e) {
                    lastError = e.getMessage();
                }
                emitStatus();
            }
        });
    }

    public void authenticate(final String methodId) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ensureStartedBlocking(BackendSettings.acpWorkspace(context));
                    if (client != null) {
                        client.authenticate(methodId);
                        authRequired = false;
                        lastError = null;
                    }
                } catch (Exception e) {
                    lastError = e.getMessage();
                }
                emitStatus();
            }
        });
    }

    public void restart() {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                stopBlocking();
                try {
                    ensureStartedBlocking(BackendSettings.acpWorkspace(context));
                } catch (Exception e) {
                    fail(e);
                }
            }
        });
    }

    public void stop() {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                stopBlocking();
                emitStatus();
            }
        });
    }

    private void stopBlocking() {
        AcpClient current = client;
        if (current != null) {
            try {
                current.close();
            } catch (Exception ignored) {
            }
        }
        Process running = process;
        if (running != null) {
            running.destroy();
        }
        client = null;
        process = null;
    }

    private void fail(Exception e) {
        lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        Log.w(TAG, "ACP runtime error: " + lastError);
        emitStatus();
    }

    private void emitStatus() {
        JSONObject event = new JSONObject();
        try {
            event.put("type", "status");
            event.put("status", status());
        } catch (Exception ignored) {
        }
        emit(event);
    }
}
