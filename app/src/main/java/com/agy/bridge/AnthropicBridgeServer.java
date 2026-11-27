package com.agy.bridge;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight local OpenAI-compatible HTTP bridge that translates /v1/models and
 * /v1/chat/completions requests to Anthropic's /v1/messages API using a
 * `claude setup-token` (sk-ant-oat01-...) or standard Anthropic API key.
 */
public final class AnthropicBridgeServer {

    private static final String TAG = "AnthropicBridge";
    private static final String ANTHROPIC_BASE = "https://api.anthropic.com/v1";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String ANTHROPIC_OAUTH_BETA = "oauth-2025-04-20";

    private static AnthropicBridgeServer instance;

    private final Context appContext;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private ServerSocket serverSocket;
    private volatile int port = -1;
    private volatile String token = "";
    private volatile double fiveHourUsed = -1;
    private volatile String fiveHourReset = "";
    private volatile double sevenDayUsed = -1;
    private volatile String sevenDayReset = "";
    private volatile String quotaStatus = "";
    private volatile long quotaUpdatedAt = 0L;

    private AnthropicBridgeServer(Context context) {
        this.appContext = context.getApplicationContext();
        start();
    }

    public static synchronized AnthropicBridgeServer getInstance(Context context) {
        if (instance == null) {
            instance = new AnthropicBridgeServer(context);
        }
        return instance;
    }

    public void setToken(String token) {
        this.token = token == null ? "" : token.trim();
    }

    public String getToken() {
        return token;
    }

    public synchronized void start() {
        if (serverSocket != null && !serverSocket.isClosed() && port > 0) {
            return;
        }
        try {
            serverSocket = new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
            port = serverSocket.getLocalPort();
            Thread acceptThread = new Thread(() -> {
                while (serverSocket != null && !serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        executor.submit(() -> handleClient(client));
                    } catch (Exception e) {
                        if (serverSocket != null && !serverSocket.isClosed()) {
                            Log.w(TAG, "Accept error: " + e.getMessage());
                        }
                    }
                }
            }, "AnthropicBridgeAccept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            Log.i(TAG, "Started AnthropicBridgeServer on 127.0.0.1:" + port);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start AnthropicBridgeServer", e);
        }
    }

    public String getBaseUrl() {
        if (port <= 0) start();
        return "http://127.0.0.1:" + port + "/v1";
    }

    private void handleClient(Socket client) {
        try (Socket s = client) {
            s.setSoTimeout(120_000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase(Locale.US);
            String path = parts[1];
            int qIdx = path.indexOf('?');
            if (qIdx >= 0) path = path.substring(0, qIdx);

            int contentLength = 0;
            String authHeader = "";
            String headerLine;
            while ((headerLine = readLine(in)) != null && !headerLine.isEmpty()) {
                int colon = headerLine.indexOf(':');
                if (colon > 0) {
                    String name = headerLine.substring(0, colon).trim().toLowerCase(Locale.US);
                    String val = headerLine.substring(colon + 1).trim();
                    if ("content-length".equals(name)) {
                        try { contentLength = Integer.parseInt(val); } catch (NumberFormatException ignored) {}
                    } else if ("authorization".equals(name)) {
                        authHeader = val;
                    }
                }
            }

            byte[] bodyBytes = new byte[0];
            if (contentLength > 0 && contentLength < 16 * 1024 * 1024) {
                bodyBytes = readExact(in, contentLength);
            }

            if ("OPTIONS".equals(method)) {
                writeJsonResponse(out, 204, "");
                return;
            }

            String activeToken = this.token;
            if ((activeToken == null || activeToken.isEmpty()) && authHeader.toLowerCase(Locale.US).startsWith("bearer ")) {
                activeToken = authHeader.substring(7).trim();
            }

            if ("GET".equals(method) && (path.endsWith("/models") || "/models".equals(path))) {
                handleModels(out, activeToken);
            } else if ("POST".equals(method) && (path.endsWith("/chat/completions") || "/chat/completions".equals(path))) {
                handleChatCompletions(out, activeToken, new String(bodyBytes, StandardCharsets.UTF_8));
            } else {
                writeJsonResponse(out, 404, "{\"error\":{\"message\":\"Not found: " + path + "\"}}");
            }
        } catch (Exception e) {
            Log.w(TAG, "Client error: " + e.getMessage());
        }
    }

    private void applyAnthropicHeaders(HttpURLConnection conn, String activeToken) {
        conn.setRequestProperty("anthropic-version", ANTHROPIC_VERSION);
        conn.setRequestProperty("Content-Type", "application/json");
        if (activeToken != null && !activeToken.isEmpty()) {
            if (activeToken.startsWith("sk-ant-oat")) {
                conn.setRequestProperty("Authorization", "Bearer " + activeToken);
                conn.setRequestProperty("anthropic-beta", ANTHROPIC_OAUTH_BETA);
                conn.setRequestProperty("User-Agent", "claude-code/2.0.0");
            } else {
                conn.setRequestProperty("x-api-key", activeToken);
            }
        }
    }

    private void captureRateLimitHeaders(HttpURLConnection conn) {
        if (conn == null) return;
        try {
            String st = conn.getHeaderField("anthropic-ratelimit-unified-status");
            String u5 = conn.getHeaderField("anthropic-ratelimit-unified-5h-utilization");
            String r5 = conn.getHeaderField("anthropic-ratelimit-unified-5h-reset");
            String u7 = conn.getHeaderField("anthropic-ratelimit-unified-7d-utilization");
            String r7 = conn.getHeaderField("anthropic-ratelimit-unified-7d-reset");
            boolean updated = false;
            if (st != null && !st.isEmpty()) { this.quotaStatus = st; updated = true; }
            if (u5 != null && !u5.isEmpty()) {
                double v = Double.parseDouble(u5);
                this.fiveHourUsed = v <= 1.0 ? v * 100.0 : v;
                updated = true;
            }
            if (r5 != null && !r5.isEmpty()) { this.fiveHourReset = r5; updated = true; }
            if (u7 != null && !u7.isEmpty()) {
                double v = Double.parseDouble(u7);
                this.sevenDayUsed = v <= 1.0 ? v * 100.0 : v;
                updated = true;
            }
            if (r7 != null && !r7.isEmpty()) { this.sevenDayReset = r7; updated = true; }
            if (updated) this.quotaUpdatedAt = System.currentTimeMillis();
        } catch (Exception ignored) {}
    }

    public String getUsageJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("status", quotaStatus);
            if (fiveHourUsed >= 0) {
                double rem5 = Math.max(0.0, Math.min(100.0, 100.0 - fiveHourUsed));
                obj.put("fiveHourUsed", Math.round(fiveHourUsed * 10.0) / 10.0);
                obj.put("fiveHourRemaining", Math.round(rem5 * 10.0) / 10.0);
            }
            obj.put("fiveHourReset", fiveHourReset);
            if (sevenDayUsed >= 0) {
                double rem7 = Math.max(0.0, Math.min(100.0, 100.0 - sevenDayUsed));
                obj.put("sevenDayUsed", Math.round(sevenDayUsed * 10.0) / 10.0);
                obj.put("sevenDayRemaining", Math.round(rem7 * 10.0) / 10.0);
            }
            obj.put("sevenDayReset", sevenDayReset);
            obj.put("updatedAt", quotaUpdatedAt);
            return obj.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    public String fetchUsageJson(String tokenOverride) {
        String activeToken = (tokenOverride != null && !tokenOverride.trim().isEmpty())
                ? tokenOverride.trim() : this.token;
        if (activeToken == null || activeToken.isEmpty()) {
            return "{\"error\":\"Missing Claude setup-token\"}";
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("https://api.anthropic.com/api/oauth/usage").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            conn.setRequestProperty("Authorization", "Bearer " + activeToken);
            conn.setRequestProperty("anthropic-beta", ANTHROPIC_OAUTH_BETA);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "claude-code/2.0.0");
            int code = conn.getResponseCode();
            captureRateLimitHeaders(conn);
            InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String resp = readAll(stream);
            conn.disconnect();
            if (code >= 200 && code < 300 && !resp.isEmpty()) {
                JSONObject uObj = new JSONObject(resp);
                JSONObject h5 = uObj.optJSONObject("five_hour");
                if (h5 != null) {
                    if (h5.has("utilization") && !h5.isNull("utilization")) {
                        double u = h5.optDouble("utilization", 0.0);
                        this.fiveHourUsed = u <= 1.0 && u > 0 && u < 1.0 ? u * 100.0 : u;
                    }
                    if (h5.has("resets_at") && !h5.isNull("resets_at")) {
                        this.fiveHourReset = h5.optString("resets_at", "");
                    }
                    this.quotaUpdatedAt = System.currentTimeMillis();
                }
                JSONObject d7 = uObj.optJSONObject("seven_day");
                if (d7 != null) {
                    if (d7.has("utilization") && !d7.isNull("utilization")) {
                        double u = d7.optDouble("utilization", 0.0);
                        this.sevenDayUsed = u <= 1.0 && u > 0 && u < 1.0 ? u * 100.0 : u;
                    }
                    if (d7.has("resets_at") && !d7.isNull("resets_at")) {
                        this.sevenDayReset = d7.optString("resets_at", "");
                    }
                    this.quotaUpdatedAt = System.currentTimeMillis();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "OAuth usage fetch failed: " + e.getMessage());
        }
        return getUsageJson();
    }

    private void handleModels(OutputStream out, String activeToken) throws Exception {
        JSONArray dataArr = new JSONArray();
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(ANTHROPIC_BASE + "/models").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            applyAnthropicHeaders(conn, activeToken);
            int code = conn.getResponseCode();
            captureRateLimitHeaders(conn);
            InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String resp = readAll(stream);
            conn.disconnect();
            if (code >= 200 && code < 300) {
                JSONObject obj = new JSONObject(resp);
                JSONArray src = obj.optJSONArray("data");
                if (src != null) {
                    for (int i = 0; i < src.length(); i++) {
                        JSONObject m = src.optJSONObject(i);
                        if (m != null && m.has("id")) {
                            JSONObject item = new JSONObject();
                            item.put("id", m.getString("id"));
                            item.put("object", "model");
                            item.put("owned_by", "anthropic");
                            dataArr.put(item);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Anthropic /models fetch failed, using defaults: " + e.getMessage());
        }

        if (dataArr.length() == 0) {
            String[] defaults = {
                "claude-sonnet-4-5-20250929",
                "claude-opus-4-5-20251101",
                "claude-3-7-sonnet-20250219",
                "claude-3-5-sonnet-20241022",
                "claude-3-5-haiku-20241022"
            };
            for (String id : defaults) {
                JSONObject item = new JSONObject();
                item.put("id", id);
                item.put("object", "model");
                item.put("owned_by", "anthropic");
                dataArr.put(item);
            }
        }

        JSONObject result = new JSONObject();
        result.put("object", "list");
        result.put("data", dataArr);
        writeJsonResponse(out, 200, result.toString());
    }

    private void handleChatCompletions(OutputStream out, String activeToken, String reqBody) throws Exception {
        if (activeToken == null || activeToken.isEmpty()) {
            writeJsonResponse(out, 401, "{\"error\":{\"message\":\"Missing Claude setup-token\"}}");
            return;
        }
        JSONObject req = new JSONObject(reqBody);
        String model = req.optString("model", "claude-sonnet-4-5-20250929");
        boolean stream = req.optBoolean("stream", false);
        int maxTokens = req.optInt("max_tokens", req.optInt("max_completion_tokens", 8192));
        if (maxTokens <= 0) maxTokens = 8192;

        JSONArray inMessages = req.optJSONArray("messages");
        StringBuilder systemSb = new StringBuilder();
        JSONArray outMessages = new JSONArray();
        if (inMessages != null) {
            for (int i = 0; i < inMessages.length(); i++) {
                JSONObject msg = inMessages.optJSONObject(i);
                if (msg == null) continue;
                String role = msg.optString("role", "user");
                Object contentObj = msg.opt("content");
                String textContent = extractText(contentObj);
                if ("system".equals(role) || "developer".equals(role)) {
                    if (!textContent.isEmpty()) {
                        if (systemSb.length() > 0) systemSb.append("\n\n");
                        systemSb.append(textContent);
                    }
                    continue;
                }

                String mappedRole;
                JSONArray blocks = new JSONArray();
                if ("tool".equals(role)) {
                    mappedRole = "user";
                    JSONObject toolRes = new JSONObject();
                    toolRes.put("type", "tool_result");
                    toolRes.put("tool_use_id", msg.optString("tool_call_id", "call_0"));
                    toolRes.put("content", textContent);
                    blocks.put(toolRes);
                } else if ("assistant".equals(role)) {
                    mappedRole = "assistant";
                    if (!textContent.isEmpty()) {
                        blocks.put(new JSONObject().put("type", "text").put("text", textContent));
                    }
                    JSONArray toolCalls = msg.optJSONArray("tool_calls");
                    if (toolCalls != null) {
                        for (int t = 0; t < toolCalls.length(); t++) {
                            JSONObject tc = toolCalls.optJSONObject(t);
                            if (tc == null) continue;
                            JSONObject fn = tc.optJSONObject("function");
                            if (fn == null) continue;
                            JSONObject tu = new JSONObject();
                            tu.put("type", "tool_use");
                            tu.put("id", tc.optString("id", "call_" + t));
                            tu.put("name", fn.optString("name", ""));
                            String argsStr = fn.optString("arguments", "{}");
                            JSONObject inputObj;
                            try {
                                inputObj = argsStr.trim().isEmpty() ? new JSONObject() : new JSONObject(argsStr);
                            } catch (Exception ignored) {
                                inputObj = new JSONObject();
                            }
                            tu.put("input", inputObj);
                            blocks.put(tu);
                        }
                    }
                    if (blocks.length() == 0) continue;
                } else {
                    mappedRole = "user";
                    if (textContent.isEmpty()) continue;
                    blocks.put(new JSONObject().put("type", "text").put("text", textContent));
                }

                // Anthropic requires alternating user/assistant roles; merge consecutive same-role messages
                if (outMessages.length() > 0) {
                    JSONObject prev = outMessages.getJSONObject(outMessages.length() - 1);
                    if (mappedRole.equals(prev.optString("role"))) {
                        JSONArray prevBlocks = prev.optJSONArray("content");
                        if (prevBlocks == null) {
                            prevBlocks = new JSONArray();
                            prev.put("content", prevBlocks);
                        }
                        for (int b = 0; b < blocks.length(); b++) {
                            prevBlocks.put(blocks.get(b));
                        }
                        continue;
                    }
                }
                JSONObject mOut = new JSONObject();
                mOut.put("role", mappedRole);
                mOut.put("content", blocks);
                outMessages.put(mOut);
            }
        }
        if (outMessages.length() == 0) {
            JSONArray fallbackBlocks = new JSONArray().put(new JSONObject().put("type", "text").put("text", " "));
            outMessages.put(new JSONObject().put("role", "user").put("content", fallbackBlocks));
        }

        JSONObject anthropicReq = new JSONObject();
        anthropicReq.put("model", model);
        anthropicReq.put("max_tokens", maxTokens);
        anthropicReq.put("stream", stream);
        if (systemSb.length() > 0) {
            anthropicReq.put("system", systemSb.toString());
        }
        if (req.has("temperature")) {
            anthropicReq.put("temperature", req.optDouble("temperature", 0.7));
        }

        // Translate OpenAI tools -> Anthropic tools
        JSONArray inTools = req.optJSONArray("tools");
        boolean disableTools = false;
        Object tcObj = req.opt("tool_choice");
        if (tcObj instanceof String && "none".equals(tcObj)) {
            disableTools = true;
        }
        if (!disableTools && inTools != null && inTools.length() > 0) {
            JSONArray aTools = new JSONArray();
            for (int i = 0; i < inTools.length(); i++) {
                JSONObject t = inTools.optJSONObject(i);
                if (t == null) continue;
                JSONObject fn = "function".equals(t.optString("type")) ? t.optJSONObject("function") : t;
                if (fn == null || !fn.has("name")) continue;
                JSONObject aTool = new JSONObject();
                aTool.put("name", fn.getString("name"));
                if (fn.has("description")) {
                    aTool.put("description", fn.optString("description", ""));
                }
                JSONObject schema = fn.optJSONObject("parameters");
                if (schema == null) {
                    schema = new JSONObject().put("type", "object").put("properties", new JSONObject());
                }
                aTool.put("input_schema", schema);
                aTools.put(aTool);
            }
            if (aTools.length() > 0) {
                anthropicReq.put("tools", aTools);
                if (tcObj instanceof String) {
                    String tcStr = (String) tcObj;
                    if ("required".equals(tcStr)) {
                        anthropicReq.put("tool_choice", new JSONObject().put("type", "any"));
                    } else if ("auto".equals(tcStr)) {
                        anthropicReq.put("tool_choice", new JSONObject().put("type", "auto"));
                    }
                } else if (tcObj instanceof JSONObject) {
                    JSONObject tcJson = (JSONObject) tcObj;
                    JSONObject tcFn = tcJson.optJSONObject("function");
                    if (tcFn != null && tcFn.has("name")) {
                        anthropicReq.put("tool_choice", new JSONObject()
                                .put("type", "tool")
                                .put("name", tcFn.getString("name")));
                    }
                }
            }
        }
        anthropicReq.put("messages", outMessages);

        HttpURLConnection conn = (HttpURLConnection) new URL(ANTHROPIC_BASE + "/messages").openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(120_000);
        applyAnthropicHeaders(conn, activeToken);
        byte[] payload = anthropicReq.toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream cos = conn.getOutputStream()) {
            cos.write(payload);
        }

        int code = conn.getResponseCode();
        captureRateLimitHeaders(conn);
        if (code < 200 || code >= 300) {
            String err = readAll(conn.getErrorStream());
            conn.disconnect();
            String formattedErr = formatOpenAiError(code, err);
            writeJsonResponse(out, code, formattedErr);
            return;
        }

        if (!stream) {
            String respStr = readAll(conn.getInputStream());
            conn.disconnect();
            JSONObject aResp = new JSONObject(respStr);
            StringBuilder textOut = new StringBuilder();
            JSONArray toolCallsOut = new JSONArray();
            JSONArray contentArr = aResp.optJSONArray("content");
            if (contentArr != null) {
                for (int i = 0; i < contentArr.length(); i++) {
                    JSONObject block = contentArr.optJSONObject(i);
                    if (block == null) continue;
                    String bType = block.optString("type");
                    if ("text".equals(bType)) {
                        textOut.append(block.optString("text", ""));
                    } else if ("tool_use".equals(bType)) {
                        JSONObject fnObj = new JSONObject();
                        fnObj.put("name", block.optString("name", ""));
                        JSONObject inp = block.optJSONObject("input");
                        fnObj.put("arguments", inp != null ? inp.toString() : "{}");
                        JSONObject tc = new JSONObject();
                        tc.put("id", block.optString("id", "call_" + i));
                        tc.put("type", "function");
                        tc.put("function", fnObj);
                        toolCallsOut.put(tc);
                    }
                }
            }
            JSONObject msgObj = new JSONObject();
            msgObj.put("role", "assistant");
            msgObj.put("content", textOut.length() > 0 ? textOut.toString() : (toolCallsOut.length() > 0 ? JSONObject.NULL : ""));
            if (toolCallsOut.length() > 0) {
                msgObj.put("tool_calls", toolCallsOut);
            }
            JSONObject choice = new JSONObject();
            choice.put("index", 0);
            choice.put("message", msgObj);
            choice.put("finish_reason", mapStopReason(aResp.optString("stop_reason", "end_turn")));

            JSONObject oaiResp = new JSONObject();
            oaiResp.put("id", aResp.optString("id", "chatcmpl-anthropic"));
            oaiResp.put("object", "chat.completion");
            oaiResp.put("created", System.currentTimeMillis() / 1000L);
            oaiResp.put("model", model);
            oaiResp.put("choices", new JSONArray().put(choice));
            writeJsonResponse(out, 200, oaiResp.toString());
            return;
        }

        // Streaming SSE translation
        String headers = "HTTP/1.1 200 OK\r\n"
                + "Content-Type: text/event-stream; charset=utf-8\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n\r\n";
        out.write(headers.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        String chunkId = "chatcmpl-" + System.currentTimeMillis();
        int toolCallIndex = -1;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;
                try {
                    JSONObject ev = new JSONObject(data);
                    String type = ev.optString("type", "");
                    if ("content_block_start".equals(type)) {
                        JSONObject cb = ev.optJSONObject("content_block");
                        if (cb != null && "tool_use".equals(cb.optString("type"))) {
                            toolCallIndex++;
                            JSONObject fn = new JSONObject()
                                    .put("name", cb.optString("name", ""))
                                    .put("arguments", "");
                            JSONObject tc = new JSONObject()
                                    .put("index", toolCallIndex)
                                    .put("id", cb.optString("id", "call_" + toolCallIndex))
                                    .put("type", "function")
                                    .put("function", fn);
                            JSONObject cDelta = new JSONObject().put("tool_calls", new JSONArray().put(tc));
                            JSONObject cChoice = new JSONObject().put("index", 0).put("delta", cDelta).put("finish_reason", JSONObject.NULL);
                            JSONObject chunk = new JSONObject()
                                    .put("id", chunkId)
                                    .put("object", "chat.completion.chunk")
                                    .put("created", System.currentTimeMillis() / 1000L)
                                    .put("model", model)
                                    .put("choices", new JSONArray().put(cChoice));
                            out.write(("data: " + chunk.toString() + "\n\n").getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        }
                    } else if ("content_block_delta".equals(type)) {
                        JSONObject delta = ev.optJSONObject("delta");
                        if (delta != null && "text_delta".equals(delta.optString("type"))) {
                            String txt = delta.optString("text", "");
                            if (!txt.isEmpty()) {
                                JSONObject cDelta = new JSONObject().put("content", txt);
                                JSONObject cChoice = new JSONObject().put("index", 0).put("delta", cDelta).put("finish_reason", JSONObject.NULL);
                                JSONObject chunk = new JSONObject()
                                        .put("id", chunkId)
                                        .put("object", "chat.completion.chunk")
                                        .put("created", System.currentTimeMillis() / 1000L)
                                        .put("model", model)
                                        .put("choices", new JSONArray().put(cChoice));
                                out.write(("data: " + chunk.toString() + "\n\n").getBytes(StandardCharsets.UTF_8));
                                out.flush();
                            }
                        } else if (delta != null && "input_json_delta".equals(delta.optString("type")) && toolCallIndex >= 0) {
                            String pj = delta.optString("partial_json", "");
                            if (!pj.isEmpty()) {
                                JSONObject fn = new JSONObject().put("arguments", pj);
                                JSONObject tc = new JSONObject().put("index", toolCallIndex).put("function", fn);
                                JSONObject cDelta = new JSONObject().put("tool_calls", new JSONArray().put(tc));
                                JSONObject cChoice = new JSONObject().put("index", 0).put("delta", cDelta).put("finish_reason", JSONObject.NULL);
                                JSONObject chunk = new JSONObject()
                                        .put("id", chunkId)
                                        .put("object", "chat.completion.chunk")
                                        .put("created", System.currentTimeMillis() / 1000L)
                                        .put("model", model)
                                        .put("choices", new JSONArray().put(cChoice));
                                out.write(("data: " + chunk.toString() + "\n\n").getBytes(StandardCharsets.UTF_8));
                                out.flush();
                            }
                        }
                    } else if ("message_delta".equals(type)) {
                        JSONObject delta = ev.optJSONObject("delta");
                        String sr = delta != null ? delta.optString("stop_reason", "stop") : "stop";
                        JSONObject cChoice = new JSONObject().put("index", 0).put("delta", new JSONObject()).put("finish_reason", mapStopReason(sr));
                        JSONObject chunk = new JSONObject()
                                .put("id", chunkId)
                                .put("object", "chat.completion.chunk")
                                .put("created", System.currentTimeMillis() / 1000L)
                                .put("model", model)
                                .put("choices", new JSONArray().put(cChoice));
                        out.write(("data: " + chunk.toString() + "\n\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    }
                } catch (Exception ignored) {}
            }
        } finally {
            conn.disconnect();
        }
        out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static String formatOpenAiError(int code, String rawErr) {
        String msg = "Anthropic HTTP " + code;
        String errType = "api_error";
        if (rawErr != null && !rawErr.trim().isEmpty()) {
            try {
                JSONObject eo = new JSONObject(rawErr);
                JSONObject inner = eo.optJSONObject("error");
                if (inner != null) {
                    String m = inner.optString("message", "");
                    String t = inner.optString("type", "");
                    if (!t.isEmpty()) errType = t;
                    if (!m.isEmpty() && !"Error".equals(m)) {
                        msg = m;
                    } else if (!t.isEmpty()) {
                        msg = "Anthropic " + t + " (HTTP " + code + ")";
                    }
                }
            } catch (Exception ignored) {
                msg = rawErr.trim();
            }
        }
        try {
            return new JSONObject().put("error", new JSONObject()
                    .put("message", msg)
                    .put("type", errType)
                    .put("code", code)).toString();
        } catch (Exception e) {
            return "{\"error\":{\"message\":\"Anthropic HTTP " + code + "\"}}";
        }
    }

    private static String mapStopReason(String sr) {
        if ("max_tokens".equals(sr)) return "length";
        if ("tool_use".equals(sr)) return "tool_calls";
        return "stop";
    }

    private static String extractText(Object contentObj) {
        if (contentObj == null) return "";
        if (contentObj instanceof String) return (String) contentObj;
        if (contentObj instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray arr = (JSONArray) contentObj;
            for (int i = 0; i < arr.length(); i++) {
                Object el = arr.opt(i);
                if (el instanceof JSONObject) {
                    JSONObject obj = (JSONObject) el;
                    if ("text".equals(obj.optString("type")) || obj.has("text")) {
                        sb.append(obj.optString("text", ""));
                    }
                } else if (el instanceof String) {
                    sb.append((String) el);
                }
            }
            return sb.toString();
        }
        return String.valueOf(contentObj);
    }

    private static void writeJsonResponse(OutputStream out, int status, String body) throws Exception {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String statusText = status == 200 ? "OK" : (status == 204 ? "No Content" : "Error");
        String hdr = "HTTP/1.1 " + status + " " + statusText + "\r\n"
                + "Content-Type: application/json; charset=utf-8\r\n"
                + "Content-Length: " + bytes.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Headers: *\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Connection: close\r\n\r\n";
        out.write(hdr.getBytes(StandardCharsets.US_ASCII));
        if (bytes.length > 0) out.write(bytes);
        out.flush();
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') baos.write(b);
        }
        if (b == -1 && baos.size() == 0) return null;
        return baos.toString("UTF-8");
    }

    private static byte[] readExact(InputStream in, int len) throws Exception {
        byte[] buf = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(buf, off, len - off);
            if (n < 0) break;
            off += n;
        }
        return buf;
    }

    private static String readAll(InputStream in) throws Exception {
        if (in == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        return sb.toString();
    }
}
