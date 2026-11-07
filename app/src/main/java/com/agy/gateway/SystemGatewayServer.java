package com.agy.gateway;

import android.content.Context;
import android.util.Log;

import com.agy.log.CrashRecorder;
import com.agy.log.LogRingBuffer;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight embedded HTTP Server listening strictly on 127.0.0.1 (default 45160, build-configurable).
 * Zero external dependencies; exposes the Android System Gateway to the Linux/agent layer.
 */
public final class SystemGatewayServer {

    private static final String TAG = "SystemGateway";
    public static final int DEFAULT_PORT = 45160;

    private final Context context;
    private int port;
    private final GatewayDispatcher dispatcher;
    private final LogRingBuffer logBuffer;
    private final ExecutorService executor = Executors.newFixedThreadPool(4);

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
                    File pFile = new File(context.getFilesDir(), "logs/gateway.port");
                    if (pFile.getParentFile() != null) pFile.getParentFile().mkdirs();
                    com.agy.util.AtomicFileWriter.writeText(pFile, String.valueOf(port) + "\n");
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

            // Read HTTP headers line by line from the raw stream
            StringBuilder headerSb = new StringBuilder();
            String reqLine = readHeaderLine(in);
            if (reqLine == null) return;

            String[] parts = reqLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0].toUpperCase();
            String path = parts[1];

            int contentLength = 0;
            String line;
            while ((line = readHeaderLine(in)) != null && !line.isEmpty()) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(15).trim());
                }
            }

            if (contentLength < 0) {
                throw new IOException("Invalid Content-Length: " + contentLength);
            }
            if (contentLength > 4 * 1024 * 1024) {
                throw new IOException("Payload too large: " + contentLength);
            }

            JSONObject responseJson = new JSONObject();
            if (path.startsWith("/gateway/status")) {
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
            } else if ("POST".equals(method) && path.startsWith("/gateway")) {
                String bodyStr = "";
                if (contentLength > 0) {
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
                    bodyStr = new String(payloadBytes, StandardCharsets.UTF_8);
                }

                JSONObject req = !bodyStr.isEmpty() ? new JSONObject(bodyStr) : new JSONObject();
                String cmd = req.optString("command", "");
                JSONObject payload = req.optJSONObject("payload");
                responseJson = dispatcher.dispatch(cmd, payload);
            } else {
                responseJson.put("ok", false);
                responseJson.put("error", "not found");
            }

            byte[] bytes = responseJson.toString().getBytes(StandardCharsets.UTF_8);
            String header = "HTTP/1.1 200 OK\r\n"
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
