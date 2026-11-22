package com.agy.core;

import android.app.NotificationManager;
import android.app.RemoteInput;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.system.Os;
import android.util.Log;

import com.agy.util.IoUtil;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Handles interactive notification action buttons (Approve / Deny / Deny w/ Comment
 * via {@link RemoteInput}, plus Subagent Cancel), stores decisions & inline comments
 * in memory and in {@code files/logs/approvals.json}, wakes waiting gateway pollers,
 * cancels the notification, and optionally forwards decisions or cancellations to
 * loopback callbacks or ConnectRPC endpoints from {@code files/logs/core.state.json}.
 */
public final class ApprovalActionReceiver extends BroadcastReceiver {

    private static final String TAG = "ApprovalActionReceiver";

    public static final String ACTION_APPROVAL_DECISION = "com.agy.ACTION_APPROVAL_DECISION";
    public static final String KEY_APPROVAL_COMMENT = "agy_approval_comment";

    public static final String EXTRA_REQUEST_ID = "request_id";
    public static final String EXTRA_DECISION = "decision";
    public static final String EXTRA_COMMENT = "comment";
    public static final String EXTRA_NOTIFICATION_TAG = "notification_tag";
    public static final String EXTRA_NOTIFICATION_ID = "notification_id";
    public static final String EXTRA_CALLBACK_URL = "callback_url";
    public static final String EXTRA_CASCADE_ID = "cascade_id";
    public static final String EXTRA_INTERACTION_ID = "interaction_id";
    public static final String EXTRA_SUBAGENT_ID = "subagent_id";
    public static final String EXTRA_ACTION_TYPE = "action_type";

    private static final String LANGUAGE_SERVER_SERVICE = "exa.language_server_pb.LanguageServerService";
    private static final String APPROVALS_REL_PATH = "logs/approvals.json";
    private static final int MAX_STORED_RECORDS = 200;
    private static final long MAX_POLL_WAIT_MS = 25000L;

    private static final ConcurrentHashMap<String, ApprovalRecord> RECORDS = new ConcurrentHashMap<>();
    private static final Object WAIT_LOCK = new Object();
    private static final Object FILE_LOCK = new Object();
    private static volatile boolean loadedFromDisk = false;

    private static final class CoreEndpoint {
        final String baseUrl;
        final String csrfToken;

        CoreEndpoint(String baseUrl, String csrfToken) {
            this.baseUrl = baseUrl;
            this.csrfToken = csrfToken != null ? csrfToken : "";
        }
    }

    private static final class ApprovalRecord {
        final String id;
        final String status;
        final String decision;
        final String comment;
        final String title;
        final String message;
        final String cascadeId;
        final String interactionId;
        final String subagentId;
        final long createdAt;
        final long decidedAt;

        ApprovalRecord(String id, String status, String decision, String comment,
                       String title, String message, String cascadeId,
                       String interactionId, String subagentId,
                       long createdAt, long decidedAt) {
            this.id = id;
            this.status = status;
            this.decision = decision;
            this.comment = comment;
            this.title = title;
            this.message = message;
            this.cascadeId = cascadeId != null ? cascadeId : "";
            this.interactionId = interactionId != null ? interactionId : "";
            this.subagentId = subagentId != null ? subagentId : "";
            this.createdAt = createdAt;
            this.decidedAt = decidedAt;
        }

        JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("id", id);
                obj.put("status", status);
                obj.put("decision", decision != null ? decision : JSONObject.NULL);
                obj.put("comment", comment != null ? comment : JSONObject.NULL);
                if (title != null && !title.isEmpty()) {
                    obj.put("title", title);
                }
                if (message != null && !message.isEmpty()) {
                    obj.put("message", message);
                }
                if (!cascadeId.isEmpty()) {
                    obj.put("cascade_id", cascadeId);
                }
                if (!interactionId.isEmpty()) {
                    obj.put("interaction_id", interactionId);
                }
                if (!subagentId.isEmpty()) {
                    obj.put("subagent_id", subagentId);
                }
                obj.put("created_at", createdAt);
                obj.put("decided_at", decidedAt);
            } catch (Exception ignored) {
            }
            return obj;
        }

        static ApprovalRecord fromJson(String fallbackId, JSONObject obj) {
            if (obj == null) {
                return null;
            }
            String id = obj.optString("id", fallbackId);
            if (id == null || id.isEmpty()) {
                return null;
            }
            String status = obj.optString("status", "pending");
            String decision = obj.isNull("decision") ? null : obj.optString("decision", null);
            String comment = obj.isNull("comment") ? null : obj.optString("comment", null);
            String title = obj.optString("title", "");
            String message = obj.optString("message", "");
            String cascadeId = obj.optString("cascade_id", "");
            String interactionId = obj.optString("interaction_id", "");
            String subagentId = obj.optString("subagent_id", "");
            long createdAt = obj.optLong("created_at", 0L);
            long decidedAt = obj.optLong("decided_at", 0L);
            return new ApprovalRecord(id, status, decision, comment, title, message,
                    cascadeId, interactionId, subagentId, createdAt, decidedAt);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null || intent == null) {
            return;
        }
        if (!ACTION_APPROVAL_DECISION.equals(intent.getAction())) {
            return;
        }
        final Context appCtx = context.getApplicationContext();
        final String requestId = intent.getStringExtra(EXTRA_REQUEST_ID);
        final String rawDecision = intent.getStringExtra(EXTRA_DECISION);
        final String notifTag = intent.getStringExtra(EXTRA_NOTIFICATION_TAG);
        final int notifId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1);
        final String callbackUrl = intent.getStringExtra(EXTRA_CALLBACK_URL);
        final String intentCascadeId = safeTrim(intent.getStringExtra(EXTRA_CASCADE_ID));
        final String intentInteractionId = safeTrim(intent.getStringExtra(EXTRA_INTERACTION_ID));
        final String intentSubagentId = safeTrim(intent.getStringExtra(EXTRA_SUBAGENT_ID));
        final String actionType = safeTrim(intent.getStringExtra(EXTRA_ACTION_TYPE));

        if (requestId == null || requestId.trim().isEmpty()
                || rawDecision == null || rawDecision.trim().isEmpty()) {
            Log.w(TAG, "ignoring approval broadcast with missing request_id or decision");
            return;
        }

        // Extract inline direct reply text from RemoteInput if present
        String comment = null;
        try {
            Bundle remoteResults = RemoteInput.getResultsFromIntent(intent);
            if (remoteResults != null) {
                CharSequence reply = remoteResults.getCharSequence(KEY_APPROVAL_COMMENT);
                if (reply != null) {
                    comment = reply.toString().trim();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "failed reading RemoteInput reply: " + t.getMessage());
        }
        if (comment == null) {
            String extraComment = intent.getStringExtra(EXTRA_COMMENT);
            if (extraComment != null) {
                comment = extraComment.trim();
            }
        }

        final String cleanId = requestId.trim();
        final String rawCleanDecision = rawDecision.trim();
        final boolean isSubagentCancel = "cancel_subagent".equalsIgnoreCase(rawCleanDecision)
                || ("cancel".equalsIgnoreCase(rawCleanDecision)
                && ("subagent".equalsIgnoreCase(actionType) || !intentSubagentId.isEmpty()));
        final String cleanDecision = isSubagentCancel ? "cancel" : rawCleanDecision;

        final ApprovalRecord updated = recordDecision(
                appCtx, cleanId, cleanDecision, comment,
                intentCascadeId, intentInteractionId, intentSubagentId);
        Log.i(TAG, "approval decision recorded: id=" + cleanId
                + " decision=" + cleanDecision
                + (comment != null && !comment.isEmpty() ? " (with comment)" : ""));

        // Cancel the notification (also clears the RemoteInput spinner immediately)
        try {
            NotificationManager nm = (NotificationManager) appCtx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && notifId >= 0) {
                if (notifTag != null && !notifTag.isEmpty()) {
                    nm.cancel(notifTag, notifId);
                } else {
                    nm.cancel(notifId);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "failed to cancel notification after decision: " + e.getMessage());
        }

        // Record in event journal
        try {
            JSONObject data = new JSONObject();
            data.put("request_id", cleanId);
            data.put("decision", cleanDecision);
            data.put("decided_at", updated.decidedAt);
            if (updated.comment != null) {
                data.put("comment", updated.comment);
            }
            if (!updated.cascadeId.isEmpty()) {
                data.put("cascade_id", updated.cascadeId);
            }
            if (!updated.interactionId.isEmpty()) {
                data.put("interaction_id", updated.interactionId);
            }
            if (!updated.subagentId.isEmpty()) {
                data.put("subagent_id", updated.subagentId);
            }
            CoreEventWatcher.appendEventLine(appCtx.getFilesDir(),
                    "agent.approval.decided", "android:notification", data);

            if (isSubagentCancel) {
                JSONObject subData = new JSONObject();
                subData.put("request_id", cleanId);
                subData.put("decision", "cancel");
                if (!updated.subagentId.isEmpty()) {
                    subData.put("subagent_id", updated.subagentId);
                }
                if (!updated.cascadeId.isEmpty()) {
                    subData.put("cascade_id", updated.cascadeId);
                }
                subData.put("decided_at", updated.decidedAt);
                CoreEventWatcher.appendEventLine(appCtx.getFilesDir(),
                        "agent.subagent.cancelled", "android:notification", subData);
            }
        } catch (Exception ignored) {
        }

        final boolean shouldLoopbackCallback = callbackUrl != null && isLoopbackCallbackUrl(callbackUrl);
        final boolean shouldCancelCascade = isSubagentCancel && !updated.cascadeId.isEmpty();
        final boolean shouldForwardInteraction = !isSubagentCancel
                && !updated.cascadeId.isEmpty()
                && (!updated.interactionId.isEmpty() || "tool_approval".equalsIgnoreCase(actionType));

        if (shouldLoopbackCallback || shouldCancelCascade || shouldForwardInteraction) {
            final PendingResult pendingResult = goAsync();
            Thread t = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        if (shouldLoopbackCallback) {
                            postLoopbackCallback(callbackUrl, updated);
                        }
                        if (shouldCancelCascade) {
                            postCancelCascadeInvocation(appCtx, updated.cascadeId);
                        } else if (shouldForwardInteraction) {
                            postHandleCascadeUserInteraction(appCtx, updated);
                        }
                    } finally {
                        try {
                            pendingResult.finish();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }, "ApprovalCallback");
            t.setDaemon(true);
            t.start();
        }
    }

    public static void registerPending(Context context, String requestId, String title, String message) {
        registerPending(context, requestId, title, message, "", "", "");
    }

    public static void registerPending(Context context, String requestId, String title, String message,
                                       String cascadeId, String interactionId, String subagentId) {
        if (requestId == null || requestId.trim().isEmpty()) {
            return;
        }
        ensureLoaded(context);
        String cleanId = requestId.trim();
        long now = System.currentTimeMillis();
        ApprovalRecord record = new ApprovalRecord(
                cleanId, "pending", null, null,
                title != null ? title : "",
                message != null ? message : "",
                safeTrim(cascadeId),
                safeTrim(interactionId),
                safeTrim(subagentId),
                now, 0L);
        RECORDS.put(cleanId, record);
        trimAndPersist(context);
    }

    public static ApprovalRecord recordDecision(Context context, String requestId, String decision) {
        return recordDecision(context, requestId, decision, null, "", "", "");
    }

    public static ApprovalRecord recordDecision(Context context, String requestId, String decision,
                                                String comment, String cascadeId,
                                                String interactionId, String subagentId) {
        ensureLoaded(context);
        String cleanId = requestId.trim();
        ApprovalRecord existing = RECORDS.get(cleanId);
        long now = System.currentTimeMillis();
        String resolvedCascade = !safeTrim(cascadeId).isEmpty()
                ? safeTrim(cascadeId)
                : (existing != null ? existing.cascadeId : "");
        String resolvedInteraction = !safeTrim(interactionId).isEmpty()
                ? safeTrim(interactionId)
                : (existing != null ? existing.interactionId : "");
        String resolvedSubagent = !safeTrim(subagentId).isEmpty()
                ? safeTrim(subagentId)
                : (existing != null ? existing.subagentId : "");

        ApprovalRecord updated = new ApprovalRecord(
                cleanId,
                "decided",
                decision,
                comment,
                existing != null ? existing.title : "",
                existing != null ? existing.message : "",
                resolvedCascade,
                resolvedInteraction,
                resolvedSubagent,
                existing != null && existing.createdAt > 0L ? existing.createdAt : now,
                now);
        RECORDS.put(cleanId, updated);
        synchronized (WAIT_LOCK) {
            WAIT_LOCK.notifyAll();
        }
        trimAndPersist(context);
        return updated;
    }

    public static JSONObject handlePoll(Context context, JSONObject payload) {
        String id = payload != null ? payload.optString("id", "").trim() : "";
        if (id.isEmpty() && payload != null) {
            id = payload.optString("request_id", "").trim();
        }
        long waitMs = payload != null ? payload.optLong("wait_ms", 0L) : 0L;
        return poll(context, id, waitMs);
    }

    public static JSONObject poll(Context context, String requestId, long waitMs) {
        JSONObject resp = new JSONObject();
        try {
            if (requestId == null || requestId.trim().isEmpty()) {
                resp.put("ok", false);
                resp.put("error", "missing approval id");
                return resp;
            }
            ensureLoaded(context);
            String cleanId = requestId.trim();
            long clampedWait = Math.max(0L, Math.min(waitMs, MAX_POLL_WAIT_MS));
            long deadline = System.currentTimeMillis() + clampedWait;

            ApprovalRecord rec = RECORDS.get(cleanId);
            if (clampedWait > 0L && (rec == null || !"decided".equals(rec.status))) {
                synchronized (WAIT_LOCK) {
                    while (true) {
                        rec = RECORDS.get(cleanId);
                        if (rec != null && "decided".equals(rec.status)) {
                            break;
                        }
                        long remaining = deadline - System.currentTimeMillis();
                        if (remaining <= 0L) {
                            break;
                        }
                        try {
                            WAIT_LOCK.wait(Math.min(remaining, 500L));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }

            resp.put("ok", true);
            resp.put("id", cleanId);
            if (rec == null) {
                resp.put("status", "unknown");
                resp.put("decision", JSONObject.NULL);
                resp.put("comment", JSONObject.NULL);
                resp.put("decided_at", 0L);
            } else {
                resp.put("status", rec.status);
                resp.put("decision", rec.decision != null ? rec.decision : JSONObject.NULL);
                resp.put("comment", rec.comment != null ? rec.comment : JSONObject.NULL);
                resp.put("decided_at", rec.decidedAt);
                if (!rec.cascadeId.isEmpty()) {
                    resp.put("cascade_id", rec.cascadeId);
                }
                if (!rec.interactionId.isEmpty()) {
                    resp.put("interaction_id", rec.interactionId);
                }
                if (!rec.subagentId.isEmpty()) {
                    resp.put("subagent_id", rec.subagentId);
                }
            }
        } catch (Exception e) {
            try {
                resp.put("ok", false);
                resp.put("error", e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return resp;
    }

    private static void ensureLoaded(Context context) {
        if (loadedFromDisk || context == null) {
            return;
        }
        synchronized (FILE_LOCK) {
            if (loadedFromDisk) {
                return;
            }
            loadedFromDisk = true;
            File file = new File(context.getApplicationContext().getFilesDir(), APPROVALS_REL_PATH);
            if (!file.isFile() || file.length() == 0L) {
                return;
            }
            try (FileInputStream in = new FileInputStream(file)) {
                String text = IoUtil.readAll(in, "UTF-8").trim();
                if (text.isEmpty()) {
                    return;
                }
                JSONObject root = new JSONObject(text);
                Iterator<String> keys = root.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    ApprovalRecord rec = ApprovalRecord.fromJson(k, root.optJSONObject(k));
                    if (rec != null && !RECORDS.containsKey(rec.id)) {
                        RECORDS.put(rec.id, rec);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "failed loading approvals store: " + e.getMessage());
            }
        }
    }

    private static void trimAndPersist(Context context) {
        if (context == null) {
            return;
        }
        synchronized (FILE_LOCK) {
            if (RECORDS.size() > MAX_STORED_RECORDS) {
                List<ApprovalRecord> list = new ArrayList<>(RECORDS.values());
                Collections.sort(list, new Comparator<ApprovalRecord>() {
                    @Override
                    public int compare(ApprovalRecord a, ApprovalRecord b) {
                        long ta = Math.max(a.createdAt, a.decidedAt);
                        long tb = Math.max(b.createdAt, b.decidedAt);
                        return Long.compare(ta, tb);
                    }
                });
                int toRemove = list.size() - MAX_STORED_RECORDS;
                for (int i = 0; i < toRemove; i++) {
                    RECORDS.remove(list.get(i).id);
                }
            }

            File target = new File(context.getApplicationContext().getFilesDir(), APPROVALS_REL_PATH);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return;
            }
            JSONObject root = new JSONObject();
            for (Map.Entry<String, ApprovalRecord> entry : RECORDS.entrySet()) {
                try {
                    root.put(entry.getKey(), entry.getValue().toJson());
                } catch (Exception ignored) {
                }
            }
            writePrivateAtomic(target, root.toString() + "\n");
        }
    }

    private static void writePrivateAtomic(File target, String content) {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            try {
                Os.chmod(tmp.getAbsolutePath(), 0600);
            } catch (Throwable ignored) {
            }
            tmp.setReadable(false, false);
            tmp.setReadable(true, true);
            tmp.setWritable(false, false);
            tmp.setWritable(true, true);
            out.write(content.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } catch (Exception e) {
            IoUtil.deleteQuietly(tmp);
            return;
        }
        if (!tmp.renameTo(target)) {
            if (!(target.delete() && tmp.renameTo(target))) {
                IoUtil.deleteQuietly(tmp);
                return;
            }
        }
        try {
            Os.chmod(target.getAbsolutePath(), 0600);
        } catch (Throwable ignored) {
        }
    }

    static boolean isLoopbackCallbackUrl(String url) {
        if (url == null) {
            return false;
        }
        try {
            Uri uri = Uri.parse(url.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return false;
            }
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                return false;
            }
            return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host);
        } catch (Exception e) {
            return false;
        }
    }

    private static CoreEndpoint loadCoreEndpoint(Context context) {
        if (context != null) {
            File stateFile = new File(context.getApplicationContext().getFilesDir(),
                    CoreProcessSupervisor.CORE_STATE_REL_PATH);
            if (stateFile.isFile() && stateFile.length() > 0L) {
                try (FileInputStream in = new FileInputStream(stateFile)) {
                    String text = IoUtil.readAll(in, "UTF-8").trim();
                    if (!text.isEmpty()) {
                        JSONObject state = new JSONObject(text);
                        if (state.optBoolean("ready", true)) {
                            String baseUrl = safeTrim(state.optString("base_url", ""));
                            int port = state.optInt("https_port", 0);
                            if (baseUrl.isEmpty() && port > 0) {
                                baseUrl = "https://127.0.0.1:" + port;
                            }
                            String csrf = safeTrim(state.optString("csrf_token", ""));
                            if (!baseUrl.isEmpty() && isLoopbackCallbackUrl(baseUrl)) {
                                return new CoreEndpoint(stripTrailingSlash(baseUrl), csrf);
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "failed reading core.state.json: " + e.getMessage());
                }
            }
        }

        int fallbackPort = CoreRuntimeState.httpsPort;
        String fallbackCsrf = CoreRuntimeState.csrfToken;
        if (fallbackPort > 0 && CoreRuntimeState.serverUrl != null) {
            return new CoreEndpoint("https://127.0.0.1:" + fallbackPort, fallbackCsrf);
        }
        return null;
    }

    private static void postCancelCascadeInvocation(Context context, String cascadeId) {
        CoreEndpoint endpoint = loadCoreEndpoint(context);
        if (endpoint == null) {
            Log.w(TAG, "CancelCascadeInvocation skipped: core endpoint unavailable");
            return;
        }
        try {
            JSONObject body = new JSONObject();
            body.put("cascadeId", cascadeId);
            int code = postConnectRpc(endpoint, "CancelCascadeInvocation", body);
            Log.i(TAG, "CancelCascadeInvocation(" + cascadeId + ") HTTP " + code);
        } catch (Exception e) {
            Log.w(TAG, "CancelCascadeInvocation failed: " + e.getMessage());
        }
    }

    private static void postHandleCascadeUserInteraction(Context context, ApprovalRecord record) {
        CoreEndpoint endpoint = loadCoreEndpoint(context);
        if (endpoint == null) {
            Log.w(TAG, "HandleCascadeUserInteraction skipped: core endpoint unavailable");
            return;
        }
        try {
            String dec = record.decision != null ? record.decision.toLowerCase(Locale.US) : "";
            boolean approved = "approve".equals(dec) || "allow".equals(dec) || "confirm".equals(dec);

            JSONObject interaction = new JSONObject();
            if (!record.interactionId.isEmpty()) {
                interaction.put("interactionId", record.interactionId);
            }
            interaction.put("approved", approved);
            interaction.put("decision", record.decision != null ? record.decision : "");
            if (record.comment != null && !record.comment.isEmpty()) {
                interaction.put("comment", record.comment);
                interaction.put("userFeedback", record.comment);
            }

            JSONObject body = new JSONObject();
            body.put("cascadeId", record.cascadeId);
            body.put("interaction", interaction);
            int code = postConnectRpc(endpoint, "HandleCascadeUserInteraction", body);
            Log.i(TAG, "HandleCascadeUserInteraction(" + record.cascadeId + ") HTTP " + code);
        } catch (Exception e) {
            Log.w(TAG, "HandleCascadeUserInteraction failed: " + e.getMessage());
        }
    }

    private static int postConnectRpc(CoreEndpoint endpoint, String method, JSONObject payload) throws Exception {
        String targetUrl = endpoint.baseUrl + "/" + LANGUAGE_SERVER_SERVICE + "/" + method;
        if (!isLoopbackCallbackUrl(targetUrl)) {
            throw new IllegalArgumentException("non-loopback ConnectRPC URL refused");
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL(targetUrl);
            conn = (HttpURLConnection) url.openConnection();
            if (conn instanceof HttpsURLConnection) {
                configureLoopbackTls((HttpsURLConnection) conn);
            }
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Connect-Protocol-Version", "1");
            if (endpoint.csrfToken != null && !endpoint.csrfToken.isEmpty()) {
                conn.setRequestProperty("x-codeium-csrf-token", endpoint.csrfToken);
            }
            byte[] bodyBytes = payload.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
                os.flush();
            }
            return conn.getResponseCode();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void postLoopbackCallback(String callbackUrl, ApprovalRecord record) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(callbackUrl);
            conn = (HttpURLConnection) url.openConnection();
            if (conn instanceof HttpsURLConnection) {
                configureLoopbackTls((HttpsURLConnection) conn);
            }
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            String csrf = CoreRuntimeState.csrfToken;
            if (csrf != null && !csrf.isEmpty()) {
                conn.setRequestProperty("x-codeium-csrf-token", csrf);
            }
            byte[] body = record.toJson().toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
                os.flush();
            }
            int code = conn.getResponseCode();
            Log.d(TAG, "loopback approval callback HTTP " + code);
        } catch (Exception e) {
            Log.w(TAG, "loopback approval callback failed: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void configureLoopbackTls(HttpsURLConnection conn) {
        try {
            TrustManager[] trustLoopback = new TrustManager[]{
                new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustLoopback, new SecureRandom());
            conn.setSSLSocketFactory(sc.getSocketFactory());
            conn.setHostnameVerifier(new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return "127.0.0.1".equals(hostname) || "localhost".equalsIgnoreCase(hostname);
                }
            });
        } catch (Exception ignored) {
        }
    }

    private static String safeTrim(String value) {
        return value == null ? "" : value.trim();
    }

    private static String stripTrailingSlash(String value) {
        String s = safeTrim(value);
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
