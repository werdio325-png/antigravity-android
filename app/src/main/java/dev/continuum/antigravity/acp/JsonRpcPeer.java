package dev.continuum.antigravity.acp;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Newline-delimited JSON-RPC 2.0 peer, as used by the Agent Client Protocol over
 * stdio.
 *
 * This class has no Android dependencies so the protocol layer can be exercised
 * on a desktop JVM against a mock agent. It is deliberately small: request
 * correlation, ordered notification dispatch, responses to agent-initiated
 * requests, and clean failure on malformed input or EOF.
 */
public final class JsonRpcPeer {

    /** JSON-RPC / ACP error surfaced to callers. */
    public static final class RpcException extends Exception {
        private final int code;

        public RpcException(int code, String message) {
            super(message == null ? "JSON-RPC error " + code : message);
            this.code = code;
        }

        public int code() {
            return code;
        }

        /** ACP uses -32000 for "authentication required". */
        public boolean isAuthenticationRequired() {
            return code == -32000;
        }
    }

    public interface NotificationHandler {
        void onNotification(String method, JSONObject params);
    }

    public interface AgentRequestHandler {
        /** Return the result object, or throw RpcException for a JSON-RPC error. */
        JSONObject handle(String method, JSONObject params) throws RpcException;
    }

    public interface CloseHandler {
        void onClosed(String reason);
    }

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final AtomicLong nextId = new AtomicLong(1);
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();

    private volatile Writer writer;
    private volatile Thread readerThread;
    private volatile boolean closed;
    private volatile NotificationHandler notificationHandler;
    private volatile AgentRequestHandler agentRequestHandler;
    private volatile CloseHandler closeHandler;

    private static final class Pending {
        final CountDownLatch done = new CountDownLatch(1);
        JSONObject result;
        RpcException error;
    }

    public void setNotificationHandler(NotificationHandler handler) {
        this.notificationHandler = handler;
    }

    public void setAgentRequestHandler(AgentRequestHandler handler) {
        this.agentRequestHandler = handler;
    }

    public void setCloseHandler(CloseHandler handler) {
        this.closeHandler = handler;
    }

    /** Starts the reader loop; returns immediately. */
    public void start(InputStream in, OutputStream out) {
        this.writer = new OutputStreamWriter(out, UTF8);
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop(in);
            }
        }, "acp-jsonrpc-reader");
        thread.setDaemon(true);
        this.readerThread = thread;
        thread.start();
    }

    public boolean isClosed() {
        return closed;
    }

    private void readLoop(InputStream in) {
        String reason = "stream closed";
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, UTF8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                handleLine(line);
            }
        } catch (Exception e) {
            reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        } finally {
            shutdown(reason);
        }
    }

    /** Exposed for tests: processes one already-decoded message. */
    public void handleLine(String line) {
        JSONObject message;
        try {
            message = new JSONObject(line);
        } catch (Exception e) {
            // Malformed input is dropped, never silently mis-parsed.
            return;
        }
        if (message.has("id") && (message.has("result") || message.has("error"))) {
            deliverResponse(message);
            return;
        }
        if (message.has("method") && message.has("id")) {
            deliverAgentRequest(message);
            return;
        }
        if (message.has("method")) {
            NotificationHandler handler = notificationHandler;
            if (handler != null) {
                handler.onNotification(message.optString("method"),
                        message.optJSONObject("params"));
            }
        }
    }

    private void deliverResponse(JSONObject message) {
        long id;
        try {
            id = message.getLong("id");
        } catch (Exception e) {
            return;
        }
        Pending entry = pending.remove(id);
        if (entry == null) {
            return; // late duplicate after a timeout; ignore, never act twice
        }
        if (message.has("error")) {
            JSONObject error = message.optJSONObject("error");
            int code = error != null ? error.optInt("code", 0) : 0;
            String text = error != null ? error.optString("message", "agent error") : "agent error";
            entry.error = new RpcException(code, text);
        } else {
            entry.result = message.optJSONObject("result");
            if (entry.result == null) {
                entry.result = new JSONObject();
            }
        }
        entry.done.countDown();
    }

    private void deliverAgentRequest(JSONObject message) {
        final long id = message.optLong("id");
        final String method = message.optString("method");
        final JSONObject params = message.optJSONObject("params");
        AgentRequestHandler handler = agentRequestHandler;
        JSONObject result = null;
        RpcException error = null;
        if (handler == null) {
            error = new RpcException(-32601, "method not supported: " + method);
        } else {
            try {
                result = handler.handle(method, params);
                if (result == null) {
                    result = new JSONObject();
                }
            } catch (RpcException e) {
                error = e;
            } catch (Exception e) {
                // A client-side handler failure must still answer the agent, or
                // the turn stalls forever.
                error = new RpcException(-32603, e.getMessage() == null
                        ? e.getClass().getSimpleName() : e.getMessage());
            }
        }
        if (error != null) {
            JSONObject payload = new JSONObject();
            try {
                payload.put("jsonrpc", "2.0");
                payload.put("id", id);
                JSONObject err = new JSONObject();
                err.put("code", error.code());
                err.put("message", error.getMessage());
                payload.put("error", err);
            } catch (Exception ignored) {
                return;
            }
            write(payload.toString());
        } else {
            JSONObject payload = new JSONObject();
            try {
                payload.put("jsonrpc", "2.0");
                payload.put("id", id);
                payload.put("result", result);
            } catch (Exception ignored) {
                return;
            }
            write(payload.toString());
        }
    }

    /** Sends a request and waits for its result. */
    public JSONObject request(String method, JSONObject params, long timeoutMs) throws RpcException {
        if (closed) {
            throw new RpcException(-32002, "agent process is not running");
        }
        long id = nextId.getAndIncrement();
        Pending entry = new Pending();
        pending.put(id, entry);
        JSONObject payload = new JSONObject();
        try {
            payload.put("jsonrpc", "2.0");
            payload.put("id", id);
            payload.put("method", method);
            if (params != null) {
                payload.put("params", params);
            }
        } catch (Exception e) {
            pending.remove(id);
            throw new RpcException(-32600, "cannot encode request: " + e.getMessage());
        }
        if (!write(payload.toString())) {
            pending.remove(id);
            throw new RpcException(-32002, "cannot write to agent process");
        }
        try {
            boolean done = entry.done.await(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
            if (!done) {
                pending.remove(id);
                throw new RpcException(-32001, "timed out waiting for " + method);
            }
        } catch (InterruptedException e) {
            pending.remove(id);
            Thread.currentThread().interrupt();
            throw new RpcException(-32001, "interrupted waiting for " + method);
        }
        if (entry.error != null) {
            throw entry.error;
        }
        return entry.result == null ? new JSONObject() : entry.result;
    }

    public boolean notify(String method, JSONObject params) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("jsonrpc", "2.0");
            payload.put("method", method);
            if (params != null) {
                payload.put("params", params);
            }
        } catch (Exception e) {
            return false;
        }
        return write(payload.toString());
    }

    private boolean write(String line) {
        Writer out = writer;
        if (out == null || closed) {
            return false;
        }
        synchronized (writeLock) {
            try {
                out.write(line);
                out.write("\n");
                out.flush();
                return true;
            } catch (IOException e) {
                shutdown("write failed: " + e.getMessage());
                return false;
            }
        }
    }

    public void close() {
        shutdown("closed by client");
    }

    private void shutdown(String reason) {
        if (closed) {
            return;
        }
        closed = true;
        for (Map.Entry<Long, Pending> entry : pending.entrySet()) {
            entry.getValue().error = new RpcException(-32002, reason);
            entry.getValue().done.countDown();
        }
        pending.clear();
        Writer out = writer;
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
            }
        }
        CloseHandler handler = closeHandler;
        if (handler != null) {
            handler.onClosed(reason);
        }
    }

    /** Number of in-flight requests; used by diagnostics and tests. */
    public int pendingCount() {
        return pending.size();
    }

    /** Waits for the reader thread to finish, mainly for tests. */
    public boolean awaitTermination(long timeoutMs) {
        Thread thread = readerThread;
        if (thread == null) {
            return true;
        }
        try {
            thread.join(timeoutMs);
            return !thread.isAlive();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
