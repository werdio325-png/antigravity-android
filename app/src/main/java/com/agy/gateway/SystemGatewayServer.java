package com.agy.gateway;

import android.content.Context;
import android.util.Log;

import com.agy.log.CrashRecorder;
import com.agy.log.LogRingBuffer;
import com.agy.util.AtomicFileWriter;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight embedded HTTP Server listening strictly on 127.0.0.1 (default 45160, build-configurable).
 * Zero external dependencies; exposes the Android System Gateway to the Linux/agent layer.
 *
 * Requests to /gateway* require a shared token (X-Agy-Gateway-Token or Authorization: Bearer)
 * persisted in files/logs/gateway.token for local trusted clients.
 */
public final class SystemGatewayServer {

    private static final String TAG = "SystemGateway";
    public static final int DEFAULT_PORT = 45160;
    private static final String TOKEN_HEADER = "x-agy-gateway-token";
    private static final String AUTH_HEADER = "authorization";

    private final Context context;
    private int port;
    private final GatewayDispatcher dispatcher;
    private final LogRingBuffer logBuffer;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private volatile String gatewayToken;

    private volatile boolean running = false;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    public SystemGatewayServer(Context context, LogRingBuffer logBuffer) {
        this(context, logBuffer, DEFAULT_PORT);
    }

    public SystemGatewayServer(Context context, LogRingBuffer logBuffer, int port) {
        this.context = context;
        this.port = port;
        this.logBuffer = logBuffer;
        this.dispatcher = new GatewayDispatcher(context, logBuffer);
        this.gatewayToken = loadOrCreateToken();
    }

    private File logsDir() {
        File dir = new File(context.getFilesDir(), "logs");
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Failed to create logs dir: " + dir);
        }
        return dir;
    }

    private String loadOrCreateToken() {
        File tokenFile = new File(logsDir(), "gateway.token");
        try {
            if (tokenFile.isFile()) {
                byte[] buf = new byte[256];
                int n;
                try (FileInputStream in = new FileInputStream(tokenFile)) {
                    n = in.read(buf);
                }
                if (n > 0) {
                    String existing = new String(buf, 0, n, StandardCharsets.UTF_8).trim();
                    if (existing.length() >= 32) {
                        return existing;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read gateway token: " + e.getMessage());
        }

        byte[] rnd = new byte[32];
        new SecureRandom().nextBytes(rnd);
        StringBuilder sb = new StringBuilder(rnd.length * 2);
        for (byte b : rnd) {
            sb.append(String.format("%02x", b & 0xff));
        }
        String token = sb.toString();
        try {
            AtomicFileWriter.writeText(tokenFile, token + "\n");
            // Best-effort: keep token private to the app user.
            tokenFile.setReadable(true, true);
            tokenFile.setWritable(true, true);
        } catch (Exception e) {
            Log.w(TAG, "Failed to persist gateway token: " + e.getMessage());
        }
        return token;
    }

    public synchronized void start() {
        if (running) return;
        running = true;

        acceptThread = new Thread(() -> {
            try {
                try {
                    serverSocket = new ServerSocket();
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50);
                } catch (IOException e) {
                    Log.w(TAG, "Port " + port + " in use, trying fallback " + (port + 2));
                    port = port + 2;
                    serverSocket = new ServerSocket();
                    serverSocket.setReuseAddress(true);
                    serverSocket.bind(new java.net.InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 50);
                }
                Log.i(TAG, "System Gateway listening on http://127.0.0.1:" + port + "/gateway");
                try {
                    File pFile = new File(logsDir(), "gateway.port");
                    AtomicFileWriter.writeText(pFile, port + "\n");
                } catch (Throwable ignored) {}

                while (running && !serverSocket.isClosed()) {
                    try {
                        Socket client = serverSocket.accept();
                        client.setSoTimeout(3000);
                        executor.submit(() -> handleClient(client));
                    } catch (Exception e) {
                        if (!running) break;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Gateway server bind failed on port " + port + ": " + e.getMessage());
            }
        }, "AgyGatewayServer");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    public synchronized void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {}
        }
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
        dispatcher.releaseResources();
        executor.shutdownNow();
        Log.i(TAG, "System Gateway stopped.");
    }

    private void handleClient(Socket client) {
        try (Socket sock = client;
             java.io.InputStream in = sock.getInputStream();
             OutputStream out = sock.getOutputStream()) {

            String reqLine = readHeaderLine(in);
            if (reqLine == null) return;

            String[] parts = reqLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase(Locale.US);
            String path = parts[1];

            Map<String, String> headers = new HashMap<>();
            int contentLength = 0;
            String line;
            while ((line = readHeaderLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String key = line.substring(0, colon).trim().toLowerCase(Locale.US);
                String value = line.substring(colon + 1).trim();
                headers.put(key, value);
                if ("content-length".equals(key)) {
                    try {
                        contentLength = Integer.parseInt(value);
                    } catch (NumberFormatException ignored) {
                        contentLength = 0;
                    }
                }
            }

            if (contentLength < 0) {
                throw new IOException("Invalid Content-Length: " + contentLength);
            }
            if (contentLength > 4 * 1024 * 1024) {
                throw new IOException("Payload too large: " + contentLength);
            }

            JSONObject responseJson = new JSONObject();
            int statusCode = 200;
            String statusText = "OK";

            if (path.startsWith("/gateway")) {
                if (!isAuthorized(headers)) {
                    statusCode = 401;
                    statusText = "Unauthorized";
                    responseJson.put("ok", false);
                    responseJson.put("error", "unauthorized");
                } else if (path.startsWith("/gateway/status")) {
                    responseJson.put("ok", true);
                    responseJson.put("status", "online");
                    responseJson.put("port", port);
                } else if (path.startsWith("/gateway/logs")) {
                    responseJson.put("ok", true);
                    responseJson.put("logs", LogRingBuffer.toJsonArray(logBuffer.getRecent(50)));
                } else if (path.startsWith("/gateway/commands")) {
                    responseJson.put("ok", true);
                    responseJson.put("commands", com.agy.log.CommandHistory.toJsonArray(dispatcher.getCommandHistory().getRecent(20)));
                } else if (path.startsWith("/gateway/crash")) {
                    JSONObject crash = CrashRecorder.getLatestCrash();
                    responseJson.put("ok", true);
                    responseJson.put("crash", crash != null ? crash : JSONObject.NULL);
                } else if ("POST".equals(method) && ("/gateway".equals(path) || path.startsWith("/gateway?"))) {
                    String bodyStr = readBody(in, contentLength);
                    JSONObject req = !bodyStr.isEmpty() ? new JSONObject(bodyStr) : new JSONObject();
                    String cmd = req.optString("command", "");
                    JSONObject payload = req.optJSONObject("payload");
                    responseJson = dispatcher.dispatch(cmd, payload);
                } else {
                    statusCode = 404;
                    statusText = "Not Found";
                    responseJson.put("ok", false);
                    responseJson.put("error", "not found");
                }
            } else {
                statusCode = 404;
                statusText = "Not Found";
                responseJson.put("ok", false);
                responseJson.put("error", "not found");
            }

            byte[] bytes = responseJson.toString().getBytes(StandardCharsets.UTF_8);
            String header = "HTTP/1.1 " + statusCode + " " + statusText + "\r\n"
                    + "Content-Type: application/json; charset=utf-8\r\n"
                    + "Content-Length: " + bytes.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(header.getBytes(StandardCharsets.UTF_8));
            out.write(bytes);
            out.flush();
        } catch (Exception e) {
            Log.d(TAG, "client error: " + e.getMessage());
        }
    }

    private boolean isAuthorized(Map<String, String> headers) {
        String expected = gatewayToken;
        if (expected == null || expected.isEmpty()) return false;
        String provided = headers.get(TOKEN_HEADER);
        if (provided == null || provided.isEmpty()) {
            String auth = headers.get(AUTH_HEADER);
            if (auth != null) {
                String prefix = "Bearer ";
                if (auth.regionMatches(true, 0, prefix, 0, prefix.length())) {
                    provided = auth.substring(prefix.length()).trim();
                }
            }
        }
        if (provided == null) return false;
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private static String readBody(java.io.InputStream in, int contentLength) throws IOException {
        if (contentLength <= 0) return "";
        byte[] payloadBytes = new byte[contentLength];
        int read = 0;
        while (read < contentLength) {
            int r = in.read(payloadBytes, read, contentLength - read);
            if (r == -1) break;
            read += r;
        }
        if (read < contentLength) {
            throw new IOException("Unexpected EOF while reading body: expected " + contentLength + ", got " + read);
        }
        return new String(payloadBytes, StandardCharsets.UTF_8);
    }

    private static final int MAX_HEADER_LINE_BYTES = 8192;

    private static String readHeaderLine(java.io.InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\r') {
                continue;
            }
            if (b == '\n') {
                break;
            }
            if (baos.size() >= MAX_HEADER_LINE_BYTES) {
                throw new IOException("HTTP header line exceeded limit of " + MAX_HEADER_LINE_BYTES + " bytes");
            }
            baos.write(b);
        }
        if (b == -1 && baos.size() == 0) {
            return null;
        }
        return baos.toString("UTF-8");
    }
}
