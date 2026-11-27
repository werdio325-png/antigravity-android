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
            } else {
                conn.setRequestProperty("x-api-key", activeToken);
            }
        }
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
                    if (systemSb.length() > 0) systemSb.append("\n\n");
                    systemSb.append(textContent);
                } else {
                    String mappedRole = "assistant".equals(role) ? "assistant" : "user";
                    JSONObject mOut = new JSONObject();
                    mOut.put("role", mappedRole);
                    mOut.put("content", textContent);
                    outMessages.put(mOut);
                }
            }
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
        if (code < 200 || code >= 300) {
            String err = readAll(conn.getErrorStream());
            conn.disconnect();
            writeJsonResponse(out, code, err.isEmpty() ? "{\"error\":{\"message\":\"Anthropic HTTP " + code + "\"}}" : err);
            return;
        }

        if (!stream) {
            String respStr = readAll(conn.getInputStream());
            conn.disconnect();
            JSONObject aResp = new JSONObject(respStr);
            StringBuilder textOut = new StringBuilder();
            JSONArray contentArr = aResp.optJSONArray("content");
            if (contentArr != null) {
                for (int i = 0; i < contentArr.length(); i++) {
                    JSONObject block = contentArr.optJSONObject(i);
                    if (block != null && "text".equals(block.optString("type"))) {
                        textOut.append(block.optString("text", ""));
                    }
                }
            }
            JSONObject msgObj = new JSONObject();
            msgObj.put("role", "assistant");
            msgObj.put("content", textOut.toString());
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
        try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;
                try {
                    JSONObject ev = new JSONObject(data);
                    String type = ev.optString("type", "");
                    if ("content_block_delta".equals(type)) {
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

    private static String mapStopReason(String sr) {
        if ("max_tokens".equals(sr)) return "length";
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
