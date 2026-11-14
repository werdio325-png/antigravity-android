import dev.continuum.antigravity.acp.AcpClient;
import dev.continuum.antigravity.acp.AcpModelCatalog;
import dev.continuum.antigravity.acp.CliRelease;
import dev.continuum.antigravity.acp.JsonRpcPeer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AcpTestMain - protocol tests for Continuum's ACP adapter.
 *
 * Runs the real adapter classes against tools/acp_test/mock_agent.py on a desktop
 * JVM. These are protocol-level tests: they prove the adapter speaks ACP
 * correctly, not that Google's servers accept a given model (that needs a device
 * and a real account).
 */
public final class AcpTestMain {

    private static String mockScript;
    private static int passed;
    private static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        mockScript = args.length > 0 ? args[0] : "tools/acp_test/mock_agent.py";
        if (!new File(mockScript).isFile()) {
            System.err.println("mock agent not found: " + mockScript);
            System.exit(2);
        }

        run("initialize negotiates protocol 1", AcpTestMain::testInitialize);
        run("CLI unavailable is reported, not ignored", AcpTestMain::testCliUnavailable);
        run("invalid CLI protocol version is refused", AcpTestMain::testInvalidProtocolVersion);
        run("authentication failure surfaces -32000", AcpTestMain::testAuthFailure);
        run("session initialization returns a session id", AcpTestMain::testSessionInit);
        run("message submission completes a turn", AcpTestMain::testPromptTurn);
        run("streaming chunks arrive in order", AcpTestMain::testStreamOrder);
        run("tool call events are delivered", AcpTestMain::testToolCall);
        run("file modification events are delivered", AcpTestMain::testToolUpdate);
        run("permission request is answered (allow)", AcpTestMain::testPermissionAllow);
        run("permission request is answered (deny)", AcpTestMain::testPermissionDeny);
        run("cancellation ends the turn", AcpTestMain::testCancel);
        run("process termination is detected", AcpTestMain::testProcessDeath);
        run("disconnect fails in-flight requests", AcpTestMain::testDisconnect);
        run("no automatic request retry after failure", AcpTestMain::testNoAutoRetry);
        run("duplicate responses are ignored", AcpTestMain::testDuplicateResponse);
        run("session restoration works", AcpTestMain::testSessionRestore);
        run("workspace isolation keeps sessions apart", AcpTestMain::testWorkspaceIsolation);
        run("model catalogue is discovered from session options", AcpTestMain::testCatalogue);
        run("Hydrogen High can be selected and exercised", AcpTestMain::testHydrogenSelection);
        run("entitlement rejection is classified, not hidden", AcpTestMain::testEntitlementRejection);
        run("quota rejection is classified separately", AcpTestMain::testQuotaRejection);
        run("successful Hydrogen inference is recorded", AcpTestMain::testHydrogenInference);
        run("release manifest is parsed and trusted-host checked", AcpTestMain::testManifest);

        System.out.println();
        System.out.println(passed + " passed, " + failures.size() + " failed");
        for (String failure : failures) {
            System.out.println("  FAILED: " + failure);
        }
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    // ---------------------------------------------------------------- harness

    private interface Checked {
        void run() throws Exception;
    }

    private static void run(String name, Checked body) {
        try {
            body.run();
            passed++;
            System.out.println("  ok   " + name);
        } catch (Throwable t) {
            failures.add(name + " -> " + t.getMessage());
            System.out.println("  FAIL " + name + " -> " + t.getMessage());
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class Recorder implements AcpClient.EventListener {
        final List<JSONObject> updates = Collections.synchronizedList(new ArrayList<>());
        final List<String> sessions = Collections.synchronizedList(new ArrayList<>());
        volatile String disconnectReason;
        volatile Boolean allow = Boolean.TRUE;
        volatile int permissionRequests;

        @Override
        public void onSessionUpdate(String sessionId, JSONObject update) {
            sessions.add(sessionId);
            updates.add(update);
        }

        @Override
        public boolean onPermissionRequest(String sessionId, JSONObject params) {
            permissionRequests++;
            return Boolean.TRUE.equals(allow);
        }

        @Override
        public void onDisconnected(String reason) {
            disconnectReason = reason;
        }
    }

    private static final class Agent implements AutoCloseable {
        final Process process;
        final AcpClient client;
        final Recorder recorder = new Recorder();

        Agent(Process process) {
            this.process = process;
            this.client = new AcpClient(recorder);
            this.client.connect(process.getInputStream(), process.getOutputStream());
        }

        @Override
        public void close() {
            try {
                client.close();
            } catch (Exception ignored) {
            }
            process.destroyForcibly();
        }
    }

    private static Agent spawn(Map<String, String> env) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("python3", mockScript);
        builder.redirectErrorStream(false);
        builder.environment().putAll(env);
        return new Agent(builder.start());
    }

    private static Agent spawn() throws Exception {
        return spawn(new HashMap<String, String>());
    }

    private static JSONObject initAndSession(Agent agent) throws Exception {
        agent.client.initialize("Continuum", "3.2.0");
        return agent.client.newSession("/work", null);
    }

    // ------------------------------------------------------------------ tests

    private static void testInitialize() throws Exception {
        try (Agent agent = spawn()) {
            JSONObject result = agent.client.initialize("Continuum", "3.2.0-test");
            check(result.optInt("protocolVersion", 0) == 1, "protocolVersion must be 1");
            check(result.optJSONObject("agentCapabilities") != null, "agent capabilities missing");
            JSONArray methods = result.optJSONArray("authMethods");
            check(methods != null && methods.length() >= 1, "auth methods missing");
            check("oauth-personal".equals(methods.optJSONObject(0).optString("id")),
                    "expected oauth-personal first");
        }
    }

    private static void testCliUnavailable() {
        boolean failed = false;
        try {
            new ProcessBuilder("/nonexistent/agy", "acp").start();
        } catch (Exception e) {
            failed = true;
        }
        check(failed, "launching a missing CLI must fail loudly");
    }

    private static void testInvalidProtocolVersion() throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("MOCK_PROTOCOL", "2");
        try (Agent agent = spawn(env)) {
            boolean refused = false;
            try {
                agent.client.initialize("Continuum", "3.2.0-test");
            } catch (JsonRpcPeer.RpcException e) {
                refused = e.getMessage().contains("supports");
            }
            check(refused, "a newer ACP protocol must be refused, not reinterpreted");
        }
    }

    private static void testAuthFailure() throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("MOCK_AUTH", "fail");
        try (Agent agent = spawn(env)) {
            agent.client.initialize("Continuum", "3.2.0-test");
            boolean authRequired = false;
            try {
                agent.client.authenticate("oauth-personal");
            } catch (JsonRpcPeer.RpcException e) {
                authRequired = e.isAuthenticationRequired();
            }
            check(authRequired, "expected -32000 authentication required");
        }
    }

    private static void testSessionInit() throws Exception {
        try (Agent agent = spawn()) {
            JSONObject result = initAndSession(agent);
            check(result.optString("sessionId", "").startsWith("sess-"), "bad session id");
            check(agent.client.currentSessionId() != null, "client did not store session id");
        }
    }

    private static void testPromptTurn() throws Exception {
        try (Agent agent = spawn()) {
            initAndSession(agent);
            JSONObject result = agent.client.prompt("Refactor the parser");
            check("end_turn".equals(result.optString("stopReason")), "expected end_turn");
        }
    }

    private static void testStreamOrder() throws Exception {
        try (Agent agent = spawn()) {
            initAndSession(agent);
            agent.client.prompt("go");
            List<JSONObject> updates = new ArrayList<>(agent.recorder.updates);
            check(updates.size() >= 3, "expected several streaming updates");
            check("agent_message_chunk".equals(updates.get(0).optString("sessionUpdate")),
                    "first update should be the message chunk");
            check("agent_thought_chunk".equals(updates.get(1).optString("sessionUpdate")),
                    "second update should be the thought chunk");
        }
    }

    private static void testToolCall() throws Exception {
        try (Agent agent = spawn()) {
            initAndSession(agent);
            agent.client.prompt("go");
            boolean toolCall = false;
            for (JSONObject update : agent.recorder.updates) {
                if ("tool_call".equals(update.optString("sessionUpdate"))) {
                    toolCall = "call-1".equals(update.optString("toolCallId"));
                }
            }
            check(toolCall, "tool_call event missing");
        }
    }

    private static void testToolUpdate() throws Exception {
        try (Agent agent = spawn()) {
            initAndSession(agent);
            agent.client.prompt("go");
            boolean completed = false;
            for (JSONObject update : agent.recorder.updates) {
                if ("tool_call_update".equals(update.optString("sessionUpdate"))
                        && "completed".equals(update.optString("status"))) {
                    completed = true;
                }
            }
            check(completed, "tool_call_update completion missing");
        }
    }

    private static void testPermissionAllow() throws Exception {
        try (Agent agent = spawn()) {
            agent.recorder.allow = true;
            initAndSession(agent);
            agent.client.prompt("go");
            check(agent.recorder.permissionRequests >= 1, "no permission request observed");
            JSONObject result = agent.client.prompt("again");
            check("end_turn".equals(result.optString("stopReason")),
                    "allowed permission must let the turn finish");
        }
    }

    private static void testPermissionDeny() throws Exception {
        try (Agent agent = spawn()) {
            agent.recorder.allow = false;
            initAndSession(agent);
            agent.client.prompt("go");
            check(agent.recorder.permissionRequests >= 1, "no permission request observed");
            check(Boolean.FALSE.equals(agent.recorder.allow), "deny path must be exercised");
        }
    }

    private static void testCancel() throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("MOCK_HANG", "1");
        try (Agent agent = spawn(env)) {
            initAndSession(agent);
            Thread turn = new Thread(() -> {
                try {
                    agent.client.prompt("long task");
                } catch (Exception ignored) {
                }
            });
            turn.start();
            Thread.sleep(400);
            agent.client.cancel();
            turn.join(8000);
            check(!turn.isAlive(), "cancelled turn did not finish");
        }
    }

    private static void testProcessDeath() throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("MOCK_DIE", "1");
        try (Agent agent = spawn(env)) {
            initAndSession(agent);
            boolean failed = false;
            try {
                agent.client.prompt("die");
            } catch (JsonRpcPeer.RpcException e) {
                failed = true;
            }
            check(failed, "prompt should fail when the agent exits");
            Thread.sleep(300);
            check(agent.recorder.disconnectReason != null, "close handler did not fire");
        }
    }

    private static void testDisconnect() throws Exception {
        try (Agent agent = spawn()) {
            initAndSession(agent);
            agent.process.destroyForcibly();
            agent.process.waitFor();
            boolean failed = false;
            try {
                agent.client.prompt("after death");
            } catch (JsonRpcPeer.RpcException e) {
                failed = true;
            }
            check(failed, "in-flight request must fail after disconnect");
            check(agent.client.peer().pendingCount() == 0, "pending requests must be cleared");
        }
    }

    private static void testNoAutoRetry() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        JsonRpcPeer peer = new JsonRpcPeer();
        // A live-but-silent agent: the request must be written once and time out
        // once, with no background resend.
        java.io.PipedOutputStream feed = new java.io.PipedOutputStream();
        java.io.PipedInputStream silent = new java.io.PipedInputStream(feed);
        peer.start(silent, sink);
        int timeouts = 0;
        for (int i = 0; i < 2; i++) {
            try {
                peer.request("session/prompt", new JSONObject(), 150);
            } catch (JsonRpcPeer.RpcException e) {
                timeouts++;
            }
        }
        check(timeouts == 2, "each request must fail once, with no queued retry");
        check(peer.pendingCount() == 0, "timed-out requests must be removed, pending=" + peer.pendingCount());
        String written = sink.toString("UTF-8");
        int frames = written.isEmpty() ? 0 : written.split("\n").length;
        check(frames == 2, "exactly two frames should be written, got " + frames + ": " + written);
    }

    private static void testDuplicateResponse() throws Exception {
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        JsonRpcPeer peer = new JsonRpcPeer();
        peer.start(new ByteArrayInputStream(new byte[0]), sink);
        String frame = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"ok\":true}}";
        peer.handleLine(frame);
        peer.handleLine(frame);
        check(peer.pendingCount() == 0, "duplicate response must not create state");
    }

    private static void testSessionRestore() throws Exception {
        try (Agent agent = spawn()) {
            JSONObject created = initAndSession(agent);
            String id = created.optString("sessionId");
            JSONObject loaded = agent.client.loadSession(id, "/work");
            check(id.equals(loaded.optString("sessionId")), "session/load returned another session");
            JSONObject turn = agent.client.prompt("continue");
            check("end_turn".equals(turn.optString("stopReason")), "restored session cannot run a turn");
        }
    }

    private static void testWorkspaceIsolation() throws Exception {
        try (Agent agent = spawn()) {
            agent.client.initialize("Continuum", "3.2.0-test");
            JSONObject first = agent.client.newSession("/work/project-a", null);
            String a = first.optString("sessionId");
            JSONObject second = agent.client.newSession("/work/project-b", null);
            String b = second.optString("sessionId");
            check(!a.equals(b), "sessions must be distinct per workspace");
            agent.client.loadSession(a, "/work/project-a");
            agent.client.prompt("in a");
            for (String session : new ArrayList<>(agent.recorder.sessions)) {
                check(session.equals(a), "update leaked from another workspace: " + session);
            }
        }
    }

    private static void testCatalogue() throws Exception {
        try (Agent agent = spawn()) {
            JSONObject result = initAndSession(agent);
            AcpModelCatalog catalog = new AcpModelCatalog();
            catalog.applySessionResult(result);
            check(catalog.entries().size() == 5, "expected 5 models, got " + catalog.entries().size());
            check(catalog.contains("gemini-4-hydrogen-high"), "Hydrogen High missing from catalogue");
            check(catalog.thinkingLevels().size() == 4, "thinking levels not discovered");
            check("model".equals(catalog.modelOptionId()), "model option id not detected");
            boolean availableBeforeUse = false;
            for (AcpModelCatalog.Entry entry : catalog.entries()) {
                if (entry.status == AcpModelCatalog.Status.AVAILABLE) {
                    availableBeforeUse = true;
                }
            }
            check(!availableBeforeUse, "nothing may be AVAILABLE before a real turn");
        }
    }

    private static void testHydrogenSelection() throws Exception {
        try (Agent agent = spawn()) {
            JSONObject result = initAndSession(agent);
            AcpModelCatalog catalog = new AcpModelCatalog();
            catalog.applySessionResult(result);
            JSONObject updated = agent.client.setConfigOption("model", "gemini-4-hydrogen-high");
            check(updated.optJSONArray("configOptions") != null, "config update returned nothing");
            JSONObject turn = agent.client.prompt("validate an Android package name");
            check("end_turn".equals(turn.optString("stopReason")), "turn failed");
            catalog.markExercised("gemini-4-hydrogen-high");
            AcpModelCatalog.Status status = AcpModelCatalog.Status.UNKNOWN;
            for (AcpModelCatalog.Entry entry : catalog.entries()) {
                if (entry.id.equals("gemini-4-hydrogen-high")) {
                    status = entry.status;
                }
            }
            check(status == AcpModelCatalog.Status.AVAILABLE, "exercised model should be AVAILABLE");
        }
    }

    private static void testEntitlementRejection() throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("MOCK_MODEL_REJECT", "plan");
        try (Agent agent = spawn(env)) {
            JSONObject result = initAndSession(agent);
            AcpModelCatalog catalog = new AcpModelCatalog();
            catalog.applySessionResult(result);
            try {
                agent.client.prompt("use argon");
            } catch (JsonRpcPeer.RpcException e) {
                catalog.markFailure("gemini-4-argon-high", e.code(), e.getMessage(),
                        e.isAuthenticationRequired());
            }
            AcpModelCatalog.Status status = null;
            for (AcpModelCatalog.Entry entry : catalog.entries()) {
                if (entry.id.equals("gemini-4-argon-high")) {
                    status = entry.status;
                }
            }
            check(status == AcpModelCatalog.Status.ACCOUNT_RESTRICTED,
                    "plan rejection must be ACCOUNT_RESTRICTED, was " + status);
        }
    }

    private static void testQuotaRejection() throws Exception {
        Map<String, String> env = new HashMap<>();
        env.put("MOCK_MODEL_REJECT", "quota");
        try (Agent agent = spawn(env)) {
            JSONObject result = initAndSession(agent);
            AcpModelCatalog catalog = new AcpModelCatalog();
            catalog.applySessionResult(result);
            try {
                agent.client.prompt("burst");
            } catch (JsonRpcPeer.RpcException e) {
                catalog.markFailure("gemini-4-hydrogen", e.code(), e.getMessage(), false);
            }
            AcpModelCatalog.Status status = null;
            for (AcpModelCatalog.Entry entry : catalog.entries()) {
                if (entry.id.equals("gemini-4-hydrogen")) {
                    status = entry.status;
                }
            }
            check(status == AcpModelCatalog.Status.QUOTA_RESTRICTED,
                    "quota rejection must be QUOTA_RESTRICTED, was " + status);
        }
    }

    private static void testHydrogenInference() throws Exception {
        try (Agent agent = spawn()) {
            initAndSession(agent);
            agent.client.prompt("Create a Kotlin utility function that validates an Android package name");
            StringBuilder text = new StringBuilder();
            for (JSONObject update : agent.recorder.updates) {
                JSONObject content = update.optJSONObject("content");
                if (content != null) {
                    text.append(content.optString("text", ""));
                }
            }
            check(text.toString().contains("fun validatePackageName"),
                    "expected the mock to return the Kotlin function; got: " + text);
        }
    }

    private static void testManifest() throws Exception {
        String json = "{\"version\":\"1.5.9\",\"url\":\"https://storage.googleapis.com/"
                + "antigravity-public/antigravity-cli/1.5.9/android-arm/cli_android_arm64.tar.gz\","
                + "\"sha512\":\"" + repeat("a", 128) + "\"}";
        CliRelease release = CliRelease.parse(json);
        check("1.5.9".equals(release.version), "version not parsed");
        check(release.isTrustedPayload(), "google storage host should be trusted");
        check(CliRelease.isNewer("1.6.0", "1.5.9"), "1.6.0 should be newer than 1.5.9");
        check(!CliRelease.isNewer("1.5.9", "1.5.9"), "same version is not newer");

        boolean rejected = false;
        try {
            CliRelease.parse("{\"version\":\"1.0\",\"url\":\"https://evil.example/x.tgz\","
                    + "\"sha512\":\"" + repeat("b", 128) + "\"}");
        } catch (IllegalArgumentException ignored) {
        }
        CliRelease evil = CliRelease.parse("{\"version\":\"1.0\",\"url\":\"https://evil.example/x.tgz\","
                + "\"sha512\":\"" + repeat("b", 128) + "\"}");
        check(!evil.isTrustedPayload(), "a foreign host must not be trusted");

        boolean badDigest = false;
        try {
            CliRelease.parse("{\"version\":\"1.0\",\"url\":\"https://storage.googleapis.com/x\","
                    + "\"sha512\":\"deadbeef\"}");
        } catch (IllegalArgumentException e) {
            badDigest = true;
        }
        check(badDigest, "a short digest must be rejected");
        check(!rejected, "");
    }

    private static String repeat(String value, int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private AcpTestMain() {
    }
}
