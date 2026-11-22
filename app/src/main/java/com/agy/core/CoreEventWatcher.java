package com.agy.core;

import android.content.Context;
import android.util.Log;

import com.agy.gateway.handlers.NotificationHandler;
import com.agy.util.SensitiveDataRedactor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Watches the core process stdout/stderr stream for agent lifecycle events
 * (task start/completion, cascade completion, rich tool approval requests,
 * subagent/swarm progress & cancellation, fatal errors), journals them to
 * {@code files/.gemini/events.jsonl}, and posts rate-limited native
 * notifications via {@link NotificationHandler}.
 */
public final class CoreEventWatcher {

    private static final String TAG = "CoreEventWatcher";

    private static final long DEFAULT_COOLDOWN_MS = 10_000L;
    private static final long APPROVAL_COOLDOWN_MS = 5_000L;
    private static final long SUBAGENT_PROGRESS_COOLDOWN_MS = 1_500L;
    private static final long MAX_EVENTS_FILE_BYTES = 2L * 1024L * 1024L;
    private static final int MAX_MESSAGE_CHARS = 240;

    private static final Pattern REQUEST_ID_PATTERN =
            Pattern.compile("(?:request_id|id)\\s*=\\s*([A-Za-z0-9_\\-\\.]+)");
    private static final Pattern CASCADE_ID_PATTERN =
            Pattern.compile("(?:cascade_id|cascadeId)\\s*=\\s*([A-Za-z0-9_\\-\\.]+)");
    private static final Pattern INTERACTION_ID_PATTERN =
            Pattern.compile("(?:interaction_id|interactionId)\\s*=\\s*([A-Za-z0-9_\\-\\.]+)");
    private static final Pattern SUBAGENT_ID_PATTERN =
            Pattern.compile("(?:subagent_id|subagentId|agent_id|worker)\\s*=\\s*([A-Za-z0-9_\\-\\.]+)");
    private static final Pattern ROLE_PATTERN =
            Pattern.compile("(?:role|name)\\s*=\\s*([A-Za-z0-9_\\-\\.]+)");
    private static final Pattern PROGRESS_PATTERN =
            Pattern.compile("progress\\s*=\\s*(\\d+)(?:/(\\d+))?");
    private static final Pattern MAX_PATTERN =
            Pattern.compile("max\\s*=\\s*(\\d+)");

    private static final Object EVENTS_FILE_LOCK = new Object();

    private final Context appContext;
    private final NotificationHandler notificationHandler;
    private final ConcurrentHashMap<String, Long> lastNotifiedAt = new ConcurrentHashMap<>();
    private volatile boolean sawTaskOrCascadeActive = false;
    private volatile String lastApprovalId = "";

    public CoreEventWatcher(Context context) {
        this(context, new NotificationHandler(context));
    }

    public CoreEventWatcher(Context context, NotificationHandler notificationHandler) {
        this.appContext = context.getApplicationContext();
        this.notificationHandler = notificationHandler != null
                ? notificationHandler
                : new NotificationHandler(this.appContext);
    }

    /** Inspects one line from core stdout/stderr. Must remain fast and non-throwing. */
    public void handleStreamLine(String rawLine) {
        if (rawLine == null || rawLine.isEmpty()) {
            return;
        }
        try {
            String safeLine = SensitiveDataRedactor.redactLine(rawLine).trim();
            if (safeLine.isEmpty()) {
                return;
            }
            String lower = safeLine.toLowerCase(Locale.US);

            // 1. Fatal errors (highest priority)
            if (lower.contains("panic:")
                    || lower.contains("fatal error:")
                    || lower.contains("sigsegv")
                    || lower.contains("sigill")) {
                handleFatalError(safeLine);
                return;
            }

            // 2. Interactive approval markers
            if (safeLine.contains("[approval:request]")
                    || safeLine.contains("ANTIGRAVITY_APPROVAL_REQUIRED")
                    || safeLine.contains("WAITING_FOR_USER")) {
                handleApprovalRequest(safeLine);
                return;
            }

            // 3. Subagent / swarm lifecycle markers
            if (safeLine.contains("[subagent:cancel]")
                    || safeLine.contains("[swarm:cancel]")
                    || safeLine.contains("SUBAGENT_CANCELLED")) {
                handleSubagentEvent(safeLine, "cancel");
                return;
            }
            if (safeLine.contains("[subagent:done]")
                    || safeLine.contains("[swarm:done]")
                    || safeLine.contains("SUBAGENT_COMPLETED")) {
                handleSubagentEvent(safeLine, "done");
                return;
            }
            if (safeLine.contains("[subagent:start]")
                    || safeLine.contains("[subagent:progress]")
                    || safeLine.contains("[swarm:start]")
                    || safeLine.contains("[swarm:progress]")
                    || safeLine.contains("SUBAGENT_STARTED")
                    || safeLine.contains("SUBAGENT_PROGRESS")) {
                boolean isStart = safeLine.contains(":start]") || safeLine.contains("SUBAGENT_STARTED");
                handleSubagentEvent(safeLine, isStart ? "start" : "progress");
                return;
            }

            // 4. Task / cascade start
            if (safeLine.contains("[task:start]")
                    || safeLine.contains("Starting task")
                    || safeLine.contains("CASCADE_RUN_STATUS_RUNNING")
                    || safeLine.contains("[cascade:start]")) {
                sawTaskOrCascadeActive = true;
                handleTaskStart(safeLine);
                return;
            }

            // 5. Task / cascade completion
            boolean explicitDone = safeLine.contains("[task:done]")
                    || safeLine.contains("Task completed")
                    || safeLine.contains("[cascade:done]")
                    || lower.contains("cascade completed");
            boolean idleAfterRun = sawTaskOrCascadeActive
                    && safeLine.contains("CASCADE_RUN_STATUS_IDLE");
            if (explicitDone || idleAfterRun) {
                sawTaskOrCascadeActive = false;
                handleTaskDone(safeLine);
            }
        } catch (Throwable t) {
            Log.w(TAG, "event watcher line error: " + t.getMessage());
        }
    }

    private void handleTaskStart(String safeLine) {
        JSONObject data = new JSONObject();
        try {
            data.put("raw", truncate(safeLine, MAX_MESSAGE_CHARS));
        } catch (Exception ignored) {
        }
        appendEventLine(appContext.getFilesDir(), "agent.task.started", "language_server:stdout", data);

        if (!allowNotification("task_start", DEFAULT_COOLDOWN_MS)) {
            return;
        }
        String summary = stripMarker(safeLine, new String[]{
                "[task:start]", "[cascade:start]", "Starting task:", "Starting task"
        });
        if (summary.isEmpty()) {
            summary = "Agent task started";
        }
        postNotification("agy_event_task", 4101, NotificationHandler.CHANNEL_TASKS,
                "Antigravity Task Started", truncate(summary, MAX_MESSAGE_CHARS), null, null);
    }

    private void handleTaskDone(String safeLine) {
        JSONObject data = new JSONObject();
        try {
            data.put("raw", truncate(safeLine, MAX_MESSAGE_CHARS));
        } catch (Exception ignored) {
        }
        appendEventLine(appContext.getFilesDir(), "agent.task.completed", "language_server:stdout", data);

        if (!allowNotification("task_done", DEFAULT_COOLDOWN_MS)) {
            return;
        }
        String summary = stripMarker(safeLine, new String[]{
                "[task:done]", "[cascade:done]", "Task completed:", "Task completed"
        });
        if (summary.isEmpty() || summary.contains("CASCADE_RUN_STATUS_IDLE")) {
            summary = "Agent finished the current task";
        }
        postNotification("agy_event_task", 4101, NotificationHandler.CHANNEL_ALERTS,
                "Antigravity Task Completed", truncate(summary, MAX_MESSAGE_CHARS), null, null);
    }

    private void handleFatalError(String safeLine) {
        JSONObject data = new JSONObject();
        try {
            data.put("message", truncate(safeLine, MAX_MESSAGE_CHARS));
            data.put("severity", "fatal");
        } catch (Exception ignored) {
        }
        appendEventLine(appContext.getFilesDir(), "system.error", "language_server:stderr", data);

        if (!allowNotification("fatal_error", DEFAULT_COOLDOWN_MS)) {
            return;
        }
        postNotification("agy_event_error", 4102, NotificationHandler.CHANNEL_ALERTS,
                "Antigravity Core Error", truncate(safeLine, MAX_MESSAGE_CHARS), null, null);
    }

    private void handleApprovalRequest(String safeLine) {
        String requestId = extractGroup(REQUEST_ID_PATTERN, safeLine);
        if (requestId.isEmpty()) {
            requestId = "core_req_" + System.currentTimeMillis();
        }
        String cascadeId = extractGroup(CASCADE_ID_PATTERN, safeLine);
        String interactionId = extractGroup(INTERACTION_ID_PATTERN, safeLine);

        JSONObject data = new JSONObject();
        try {
            data.put("request_id", requestId);
            if (!cascadeId.isEmpty()) {
                data.put("cascade_id", cascadeId);
            }
            if (!interactionId.isEmpty()) {
                data.put("interaction_id", interactionId);
            }
            data.put("raw", truncate(safeLine, MAX_MESSAGE_CHARS));
        } catch (Exception ignored) {
        }
        appendEventLine(appContext.getFilesDir(), "agent.approval.requested", "language_server:stdout", data);

        boolean sameId = requestId.equals(lastApprovalId);
        if (sameId && !allowNotification("approval_" + requestId, APPROVAL_COOLDOWN_MS)) {
            return;
        }
        if (!sameId && !allowNotification("approval_global", 1500L)) {
            return;
        }
        lastApprovalId = requestId;

        String summary = stripMarker(safeLine, new String[]{
                "[approval:request]", "ANTIGRAVITY_APPROVAL_REQUIRED:", "ANTIGRAVITY_APPROVAL_REQUIRED",
                "WAITING_FOR_USER:", "WAITING_FOR_USER"
        });
        if (summary.isEmpty()) {
            summary = "Agent action requires your approval";
        }

        int notifId = 4200 + Math.abs(requestId.hashCode() % 500);
        try {
            JSONObject payload = new JSONObject();
            payload.put("action", "post");
            payload.put("type", "tool_approval");
            payload.put("tag", "agy_event_approval");
            payload.put("id", notifId);
            payload.put("channel", NotificationHandler.CHANNEL_ALERTS);
            payload.put("title", "Approval Required");
            payload.put("message", truncate(summary, MAX_MESSAGE_CHARS));
            payload.put("request_id", requestId);
            if (!cascadeId.isEmpty()) {
                payload.put("cascade_id", cascadeId);
            }
            if (!interactionId.isEmpty()) {
                payload.put("interaction_id", interactionId);
            }
            notificationHandler.handle(payload);
        } catch (Exception e) {
            Log.w(TAG, "failed posting approval notification: " + e.getMessage());
        }
    }

    private void handleSubagentEvent(String safeLine, String phase) {
        String subagentId = extractGroup(SUBAGENT_ID_PATTERN, safeLine);
        String cascadeId = extractGroup(CASCADE_ID_PATTERN, safeLine);
        String role = extractGroup(ROLE_PATTERN, safeLine);
        if (subagentId.isEmpty()) {
            subagentId = !cascadeId.isEmpty() ? cascadeId : (!role.isEmpty() ? role : "default");
        }

        int progress = -1;
        int max = 100;
        Matcher pm = PROGRESS_PATTERN.matcher(safeLine);
        if (pm.find()) {
            try {
                progress = Integer.parseInt(pm.group(1));
                if (pm.group(2) != null) {
                    max = Math.max(1, Integer.parseInt(pm.group(2)));
                }
            } catch (NumberFormatException ignored) {
            }
        }
        String maxStr = extractGroup(MAX_PATTERN, safeLine);
        if (!maxStr.isEmpty()) {
            try {
                max = Math.max(1, Integer.parseInt(maxStr));
            } catch (NumberFormatException ignored) {
            }
        }

        String eventName;
        if ("cancel".equals(phase)) {
            eventName = "agent.subagent.cancelled";
        } else if ("done".equals(phase)) {
            eventName = "agent.subagent.completed";
        } else if ("start".equals(phase)) {
            eventName = "agent.subagent.started";
        } else {
            eventName = "agent.subagent.progress";
        }

        JSONObject data = new JSONObject();
        try {
            data.put("subagent_id", subagentId);
            if (!cascadeId.isEmpty()) {
                data.put("cascade_id", cascadeId);
            }
            if (!role.isEmpty()) {
                data.put("role", role);
            }
            if (progress >= 0) {
                data.put("progress", progress);
                data.put("max", max);
            }
            data.put("raw", truncate(safeLine, MAX_MESSAGE_CHARS));
        } catch (Exception ignored) {
        }
        appendEventLine(appContext.getFilesDir(), eventName, "language_server:stdout", data);

        if ("progress".equals(phase)
                && !allowNotification("subagent_prog_" + subagentId, SUBAGENT_PROGRESS_COOLDOWN_MS)) {
            return;
        }

        String summary = stripMarker(safeLine, new String[]{
                "[subagent:start]", "[subagent:progress]", "[subagent:done]", "[subagent:cancel]",
                "[swarm:start]", "[swarm:progress]", "[swarm:done]", "[swarm:cancel]",
                "SUBAGENT_STARTED:", "SUBAGENT_STARTED",
                "SUBAGENT_PROGRESS:", "SUBAGENT_PROGRESS",
                "SUBAGENT_COMPLETED:", "SUBAGENT_COMPLETED",
                "SUBAGENT_CANCELLED:", "SUBAGENT_CANCELLED"
        });

        try {
            JSONObject payload = new JSONObject();
            payload.put("subagent_id", subagentId);
            if (!cascadeId.isEmpty()) {
                payload.put("cascade_id", cascadeId);
            }
            if (!role.isEmpty()) {
                payload.put("role", role);
            }
            if ("cancel".equals(phase)) {
                payload.put("action", "cancel");
            } else if ("done".equals(phase)) {
                payload.put("action", "done");
                payload.put("message", summary.isEmpty() ? "Subagent completed" : truncate(summary, MAX_MESSAGE_CHARS));
                payload.put("cancellable", false);
                payload.put("ongoing", false);
            } else {
                payload.put("action", "post");
                payload.put("message", summary.isEmpty() ? "Running..." : truncate(summary, MAX_MESSAGE_CHARS));
                payload.put("cancellable", true);
                payload.put("ongoing", true);
                if (progress >= 0) {
                    payload.put("progress", progress);
                    payload.put("max", max);
                } else {
                    payload.put("indeterminate", true);
                }
            }
            notificationHandler.handleSubagent(payload);
        } catch (Exception e) {
            Log.w(TAG, "failed posting subagent notification: " + e.getMessage());
        }
    }

    private boolean allowNotification(String key, long cooldownMs) {
        long now = System.currentTimeMillis();
        Long prev = lastNotifiedAt.get(key);
        if (prev != null && (now - prev) < cooldownMs) {
            return false;
        }
        lastNotifiedAt.put(key, now);
        return true;
    }

    private void postNotification(String tag, int id, String channel, String title,
                                  String message, String requestId, JSONArray actions) {
        try {
            JSONObject payload = new JSONObject();
            payload.put("action", "post");
            payload.put("tag", tag);
            payload.put("id", id);
            payload.put("channel", channel);
            payload.put("title", title);
            payload.put("message", message);
            if (requestId != null && !requestId.isEmpty()) {
                payload.put("request_id", requestId);
            }
            if (actions != null && actions.length() > 0) {
                payload.put("actions", actions);
            }
            notificationHandler.handle(payload);
        } catch (Exception e) {
            Log.w(TAG, "failed posting event notification: " + e.getMessage());
        }
    }

    /** Appends a single JSONL event envelope to {@code files/.gemini/events.jsonl}. */
    public static void appendEventLine(File filesDir, String eventName, String source, JSONObject data) {
        if (filesDir == null || eventName == null || eventName.isEmpty()) {
            return;
        }
        synchronized (EVENTS_FILE_LOCK) {
            try {
                File geminiDir = new File(filesDir, ".gemini");
                if (!geminiDir.exists() && !geminiDir.mkdirs()) {
                    return;
                }
                File eventsFile = new File(geminiDir, "events.jsonl");
                if (eventsFile.isFile() && eventsFile.length() > MAX_EVENTS_FILE_BYTES) {
                    File rotated = new File(geminiDir, "events.jsonl.1");
                    if (rotated.exists()) {
                        rotated.delete();
                    }
                    eventsFile.renameTo(rotated);
                }

                JSONObject envelope = new JSONObject();
                envelope.put("event", eventName);
                envelope.put("timestamp", System.currentTimeMillis());
                envelope.put("source", source != null ? source : "android:core");
                envelope.put("data", data != null ? data : new JSONObject());

                byte[] bytes = (envelope.toString() + "\n").getBytes(StandardCharsets.UTF_8);
                try (FileOutputStream fos = new FileOutputStream(eventsFile, true)) {
                    fos.write(bytes);
                }
            } catch (Exception e) {
                Log.d(TAG, "failed appending event line: " + e.getMessage());
            }
        }
    }

    private static String extractGroup(Pattern pattern, String line) {
        Matcher m = pattern.matcher(line);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }

    private static String stripMarker(String line, String[] markers) {
        for (String marker : markers) {
            int idx = line.indexOf(marker);
            if (idx >= 0) {
                String tail = line.substring(idx + marker.length()).trim();
                if (!tail.isEmpty()) {
                    return tail;
                }
            }
        }
        return line.trim();
    }

    private static String truncate(String s, int maxChars) {
        if (s == null) {
            return "";
        }
        if (s.length() <= maxChars) {
            return s;
        }
        return s.substring(0, Math.max(0, maxChars - 1)) + "…";
    }
}
