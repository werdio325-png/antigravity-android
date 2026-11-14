package dev.continuum.antigravity.acp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Client side of the Agent Client Protocol, speaking to `agy acp`.
 *
 * Method names, session options and authentication ids follow Google's
 * documentation for Antigravity CLI ACP; nothing here invents protocol surface.
 * The class is Android-free so it can be tested against a mock agent.
 */
public final class AcpClient {

    /** ACP protocol version this client implements. */
    public static final int PROTOCOL_VERSION = 1;
    public static final int DEFAULT_TIMEOUT_MS = 30000;
    public static final int PROMPT_TIMEOUT_MS = 30 * 60 * 1000;

    public interface EventListener {
        /** Ordered session/update notifications, plus lifecycle events. */
        void onSessionUpdate(String sessionId, JSONObject update);

        /** Agent asked for permission; return true to allow. Called off the UI thread. */
        boolean onPermissionRequest(String sessionId, JSONObject params);

        void onDisconnected(String reason);
    }

    private final JsonRpcPeer peer = new JsonRpcPeer();
    private final EventListener listener;
    private final List<String> transcriptMethods = new ArrayList<>();

    private volatile boolean authenticated;
    private volatile String sessionId;

    public AcpClient(EventListener listener) {
        this.listener = listener;
        peer.setNotificationHandler(new JsonRpcPeer.NotificationHandler() {
            @Override
            public void onNotification(String method, JSONObject params) {
                if ("session/update".equals(method) && AcpClient.this.listener != null) {
                    String session = params != null ? params.optString("sessionId", "") : "";
                    JSONObject update = params != null ? params.optJSONObject("update") : null;
                    if (update != null) {
                        AcpClient.this.listener.onSessionUpdate(session, update);
                    }
                }
            }
        });
        peer.setAgentRequestHandler(new JsonRpcPeer.AgentRequestHandler() {
            @Override
            public JSONObject handle(String method, JSONObject params) throws JsonRpcPeer.RpcException {
                if ("session/request_permission".equals(method)) {
                    boolean allow = AcpClient.this.listener != null
                            && AcpClient.this.listener.onPermissionRequest(
                                    params != null ? params.optString("sessionId", "") : "", params);
                    JSONObject result = new JSONObject();
                    try {
                        // Deny is the safe default: an unanswered request must never
                        // turn into an approval.
                        result.put("outcome", allow ? allowOutcome(params) : denyOutcome(params));
                    } catch (Exception ignored) {
                    }
                    return result;
                }
                // Capabilities we did not advertise must be refused explicitly.
                throw new JsonRpcPeer.RpcException(-32601, "unsupported client method: " + method);
            }
        });
        peer.setCloseHandler(new JsonRpcPeer.CloseHandler() {
            @Override
            public void onClosed(String reason) {
                sessionId = null;
                if (AcpClient.this.listener != null) {
                    AcpClient.this.listener.onDisconnected(reason);
                }
            }
        });
    }

    private static JSONObject allowOutcome(JSONObject params) {
        JSONObject outcome = new JSONObject();
        try {
            outcome.put("outcome", "selected");
            outcome.put("optionId", pickOptionId(params, true));
        } catch (Exception ignored) {
        }
        return outcome;
    }

    private static JSONObject denyOutcome(JSONObject params) {
        JSONObject outcome = new JSONObject();
        try {
            outcome.put("outcome", "selected");
            outcome.put("optionId", pickOptionId(params, false));
        } catch (Exception ignored) {
        }
        return outcome;
    }

    /** Chooses the allow/deny option the agent offered, by kind. */
    private static String pickOptionId(JSONObject params, boolean allow) {
        JSONArray options = params != null ? params.optJSONArray("options") : null;
        String fallback = allow ? "allow" : "deny";
        String wanted = allow ? "allow" : "reject";
        for (int i = 0; options != null && i < options.length(); i++) {
            JSONObject option = options.optJSONObject(i);
            if (option == null) {
                continue;
            }
            String kind = option.optString("kind", "").toLowerCase();
            String id = option.optString("optionId", option.optString("id", ""));
            if (id.isEmpty()) {
                continue;
            }
            if (allow && (kind.startsWith("allow") || kind.equals("approve"))) {
                return id;
            }
            if (!allow && (kind.startsWith("reject") || kind.startsWith("deny"))) {
                return id;
            }
            if (kind.contains(wanted)) {
                return id;
            }
            if (i == 0 && !allow && options.length() == 1) {
                return id;
            }
        }
        return fallback;
    }

    public void connect(InputStream in, OutputStream out) {
        peer.start(in, out);
    }

    public JsonRpcPeer peer() {
        return peer;
    }

    /** initialize: version + client capabilities, returns agent capabilities. */
    public JSONObject initialize(String clientName, String clientVersion) throws JsonRpcPeer.RpcException {
        JSONObject capabilities = new JSONObject();
        try {
            JSONObject fs = new JSONObject();
            // We bridge file access through the Android process, so these are
            // advertised and served by the host (see AcpBackendService).
            fs.put("readTextFile", false);
            fs.put("writeTextFile", false);
            capabilities.put("fs", fs);
            capabilities.put("terminal", false);
        } catch (Exception ignored) {
        }
        JSONObject params = new JSONObject();
        try {
            params.put("protocolVersion", PROTOCOL_VERSION);
            JSONObject client = new JSONObject();
            client.put("name", clientName);
            client.put("version", clientVersion);
            params.put("clientInfo", client);
            params.put("clientCapabilities", capabilities);
        } catch (Exception ignored) {
        }
        JSONObject result = peer.request("initialize", params, DEFAULT_TIMEOUT_MS);
        if (result.optInt("protocolVersion", 0) > PROTOCOL_VERSION) {
            // Never reinterpret a newer protocol as v1.
            throw new JsonRpcPeer.RpcException(-32600,
                    "agent speaks ACP protocol " + result.optInt("protocolVersion", 0)
                            + ", this build supports " + PROTOCOL_VERSION);
        }
        return result;
    }

    public JSONObject authenticate(String methodId) throws JsonRpcPeer.RpcException {
        JSONObject params = new JSONObject();
        try {
            params.put("methodId", methodId);
        } catch (Exception ignored) {
        }
        JSONObject result = peer.request("authenticate", params, DEFAULT_TIMEOUT_MS);
        authenticated = true;
        return result;
    }

    public boolean isAuthenticated() {
        return authenticated;
    }

    /** session/new: creates a session and returns its config options (models). */
    public JSONObject newSession(String cwd, List<String> additionalDirectories)
            throws JsonRpcPeer.RpcException {
        JSONObject params = new JSONObject();
        try {
            params.put("cwd", cwd);
            params.put("mcpServers", new JSONArray());
            if (additionalDirectories != null && !additionalDirectories.isEmpty()) {
                JSONArray dirs = new JSONArray();
                for (String dir : additionalDirectories) {
                    dirs.put(dir);
                }
                params.put("additionalDirectories", dirs);
            }
        } catch (Exception ignored) {
        }
        JSONObject result = peer.request("session/new", params, DEFAULT_TIMEOUT_MS);
        sessionId = result.optString("sessionId", null);
        if (sessionId == null || sessionId.isEmpty()) {
            throw new JsonRpcPeer.RpcException(-32603, "agent returned no sessionId");
        }
        return result;
    }

    /** session/load: resume an existing conversation (requires loadSession). */
    public JSONObject loadSession(String existingSessionId, String cwd)
            throws JsonRpcPeer.RpcException {
        JSONObject params = new JSONObject();
        try {
            params.put("sessionId", existingSessionId);
            params.put("cwd", cwd);
            params.put("mcpServers", new JSONArray());
        } catch (Exception ignored) {
        }
        JSONObject result = peer.request("session/load", params, DEFAULT_TIMEOUT_MS);
        sessionId = existingSessionId;
        return result;
    }

    /** session/prompt: sends one user turn; resolves when the turn ends. */
    public JSONObject prompt(String text) throws JsonRpcPeer.RpcException {
        String session = sessionId;
        if (session == null) {
            throw new JsonRpcPeer.RpcException(-32600, "no active session");
        }
        JSONArray blocks = new JSONArray();
        JSONObject block = new JSONObject();
        try {
            block.put("type", "text");
            block.put("text", text);
            blocks.put(block);
        } catch (Exception ignored) {
        }
        JSONObject params = new JSONObject();
        try {
            params.put("sessionId", session);
            params.put("prompt", blocks);
        } catch (Exception ignored) {
        }
        return peer.request("session/prompt", params, PROMPT_TIMEOUT_MS);
    }

    /** session/cancel: a notification, not a request. */
    public boolean cancel() {
        String session = sessionId;
        if (session == null) {
            return false;
        }
        JSONObject params = new JSONObject();
        try {
            params.put("sessionId", session);
        } catch (Exception ignored) {
        }
        return peer.notify("session/cancel", params);
    }

    /** session/set_config_option: model and thinking level selection. */
    public JSONObject setConfigOption(String configId, String value)
            throws JsonRpcPeer.RpcException {
        String session = sessionId;
        if (session == null) {
            throw new JsonRpcPeer.RpcException(-32600, "no active session");
        }
        JSONObject params = new JSONObject();
        try {
            params.put("sessionId", session);
            params.put("configId", configId);
            params.put("value", value);
        } catch (Exception ignored) {
        }
        return peer.request("session/set_config_option", params, DEFAULT_TIMEOUT_MS);
    }

    /** Method log, for diagnostics only (no payloads, so no prompt text). */
    public List<String> methodLog() {
        return transcriptMethods;
    }

    public String currentSessionId() {
        return sessionId;
    }

    public void close() {
        peer.close();
    }
}
