package com.agy.gateway.handlers;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import com.agy.core.ApprovalActionReceiver;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Handles native Android notifications (title, message, progress, channels, cancel),
 * rich tool approvals (Approve / Deny / Deny w/ Comment via {@link RemoteInput}),
 * and subagent/swarm progress notifications with ConnectRPC cancellation.
 */
public final class NotificationHandler {

    private static final String TAG = "NotificationHandler";
    public static final String CHANNEL_TASKS = "agy_tasks";
    public static final String CHANNEL_ALERTS = "agy_alerts";
    public static final String CHANNEL_SUBAGENTS = "agy_subagents";

    private static final String DEFAULT_REPLY_LABEL = "Reason / feedback...";

    private final Context context;
    private final NotificationManager manager;

    public NotificationHandler(Context context) {
        this.context = context.getApplicationContext();
        this.manager = (NotificationManager) this.context.getSystemService(Context.NOTIFICATION_SERVICE);
        initChannels();
    }

    private void initChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            NotificationChannel tasks = new NotificationChannel(
                    CHANNEL_TASKS, "Antigravity Tasks", NotificationManager.IMPORTANCE_DEFAULT);
            tasks.setDescription("Notifications for ongoing AI agent and environment tasks");
            manager.createNotificationChannel(tasks);

            NotificationChannel alerts = new NotificationChannel(
                    CHANNEL_ALERTS, "Antigravity Alerts", NotificationManager.IMPORTANCE_HIGH);
            alerts.setDescription("High-priority alerts, tool approvals, and task completions");
            manager.createNotificationChannel(alerts);

            NotificationChannel subagents = new NotificationChannel(
                    CHANNEL_SUBAGENTS, "Antigravity Subagents", NotificationManager.IMPORTANCE_LOW);
            subagents.setDescription("Live progress and cancellation controls for running subagents and swarms");
            subagents.setShowBadge(false);
            manager.createNotificationChannel(subagents);
        }
    }

    public JSONObject handle(JSONObject payload) {
        if (payload == null) {
            payload = new JSONObject();
        }
        String type = payload.optString("type", "").trim();
        if ("subagent".equalsIgnoreCase(type)) {
            return handleSubagent(payload);
        }
        return postOrCancel(payload, null);
    }

    /** Handles {@code command: "subagent_notify"} and {@code notify} with {@code type: "subagent"}. */
    public JSONObject handleSubagent(JSONObject payload) {
        if (payload == null) {
            payload = new JSONObject();
        }
        JSONObject normalized = new JSONObject();
        try {
            String action = payload.optString("action", "post").trim();
            String subagentId = payload.optString("subagent_id", "").trim();
            String cascadeId = payload.optString("cascade_id", "").trim();
            String role = payload.optString("role", "").trim();
            String title = payload.optString("title", "").trim();
            String message = payload.optString("message", "").trim();

            String stableKey = !subagentId.isEmpty() ? subagentId : cascadeId;
            String defaultTag = stableKey.isEmpty()
                    ? "agy_subagent"
                    : "agy_subagent_" + sanitizeTag(stableKey);
            int defaultId = stableKey.isEmpty()
                    ? 5100
                    : 5100 + Math.abs(stableKey.hashCode() % 1000);

            String tag = payload.optString("tag", defaultTag);
            if (tag == null || tag.trim().isEmpty()) {
                tag = defaultTag;
            }
            int id = payload.has("id") ? payload.optInt("id", defaultId) : defaultId;

            if ("cancel".equalsIgnoreCase(action) || "dismiss".equalsIgnoreCase(action)) {
                normalized.put("action", "cancel");
                normalized.put("tag", tag);
                normalized.put("id", id);
                JSONObject resp = postOrCancel(normalized, "subagent");
                if (!subagentId.isEmpty()) {
                    resp.put("subagent_id", subagentId);
                }
                return resp;
            }

            boolean isDone = "done".equalsIgnoreCase(action)
                    || payload.optBoolean("done", false)
                    || payload.optBoolean("completed", false);

            if (title.isEmpty()) {
                title = !role.isEmpty() ? ("Subagent: " + role) : "Antigravity Subagent";
            }
            if (message.isEmpty()) {
                message = isDone ? "Subagent finished" : "Working...";
            }

            String channel = payload.optString("channel", CHANNEL_SUBAGENTS).trim();
            if (channel.isEmpty()) {
                channel = CHANNEL_SUBAGENTS;
            }
            boolean ongoing = payload.has("ongoing") ? payload.optBoolean("ongoing", !isDone) : !isDone;
            boolean cancellable = !isDone && payload.optBoolean("cancellable", true);

            normalized.put("action", "post");
            normalized.put("tag", tag);
            normalized.put("id", id);
            normalized.put("channel", channel);
            normalized.put("title", title);
            normalized.put("message", message);
            normalized.put("ongoing", ongoing);
            if (payload.has("progress")) {
                normalized.put("progress", payload.optInt("progress", -1));
            }
            if (payload.has("max")) {
                normalized.put("max", payload.optInt("max", 100));
            }
            if (payload.has("indeterminate")) {
                normalized.put("indeterminate", payload.optBoolean("indeterminate", false));
            }
            if (payload.has("progress_mode")) {
                normalized.put("progress_mode", payload.optString("progress_mode", ""));
            }
            if (!subagentId.isEmpty()) {
                normalized.put("subagent_id", subagentId);
            }
            if (!cascadeId.isEmpty()) {
                normalized.put("cascade_id", cascadeId);
            }
            String callbackUrl = payload.optString("callback_url", "").trim();
            if (!callbackUrl.isEmpty()) {
                normalized.put("callback_url", callbackUrl);
            }

            JSONArray actions = payload.optJSONArray("actions");
            if (cancellable && (actions == null || actions.length() == 0)) {
                actions = new JSONArray();
                JSONObject cancelAct = new JSONObject();
                cancelAct.put("id", "cancel_subagent");
                cancelAct.put("label", "Cancel");
                actions.put(cancelAct);
            }
            if (actions != null && actions.length() > 0) {
                normalized.put("actions", actions);
                String requestId = payload.optString("request_id", "").trim();
                if (requestId.isEmpty()) {
                    requestId = "subagent_" + (!stableKey.isEmpty() ? stableKey : String.valueOf(id));
                }
                normalized.put("request_id", requestId);
            }

            JSONObject resp = postOrCancel(normalized, "subagent");
            if (!subagentId.isEmpty()) {
                resp.put("subagent_id", subagentId);
            }
            return resp;
        } catch (Exception e) {
            JSONObject err = new JSONObject();
            try {
                err.put("ok", false);
                err.put("error", e.getMessage());
            } catch (Exception ignored) {
            }
            return err;
        }
    }

    private JSONObject postOrCancel(JSONObject payload, String explicitActionType) {
        JSONObject resp = new JSONObject();
        try {
            String action = payload.optString("action", "post");
            String tag = payload.optString("tag", "agy_task");
            int id = payload.optInt("id", (int) (System.currentTimeMillis() % 100000));

            if ("cancel".equalsIgnoreCase(action)) {
                if (manager != null) {
                    manager.cancel(tag, id);
                }
                resp.put("ok", true);
                resp.put("cancelled", true);
                resp.put("tag", tag);
                resp.put("id", id);
                return resp;
            }

            String type = payload.optString("type", "").trim();
            boolean isToolApproval = payload.optBoolean("approval", false)
                    || "tool_approval".equalsIgnoreCase(type);
            String actionType = explicitActionType != null
                    ? explicitActionType
                    : (isToolApproval ? "tool_approval" : type);

            String title = payload.optString("title", isToolApproval ? "Approval Required" : "Antigravity");
            String message = payload.optString("message", "");
            int progress = payload.optInt("progress", -1);
            int max = Math.max(1, payload.optInt("max", 100));
            boolean indeterminate = payload.optBoolean("indeterminate", false)
                    || "indeterminate".equalsIgnoreCase(payload.optString("progress_mode"));
            boolean ongoing = payload.optBoolean("ongoing", false);
            String defaultReplyLabel = payload.optString("reply_label", DEFAULT_REPLY_LABEL).trim();
            if (defaultReplyLabel.isEmpty()) {
                defaultReplyLabel = DEFAULT_REPLY_LABEL;
            }

            JSONArray actions = payload.optJSONArray("actions");
            if (isToolApproval && (actions == null || actions.length() == 0)) {
                actions = buildDefaultToolApprovalActions(defaultReplyLabel);
            }
            boolean hasActions = actions != null && actions.length() > 0;

            String defaultChannel = (isToolApproval || hasActions) ? CHANNEL_ALERTS : CHANNEL_TASKS;
            String channel = payload.optString("channel", defaultChannel);
            if (channel == null || channel.trim().isEmpty()) {
                channel = defaultChannel;
            }
            String requestId = payload.optString("request_id", "").trim();
            String callbackUrl = payload.optString("callback_url", "").trim();
            String cascadeId = payload.optString("cascade_id", "").trim();
            String interactionId = payload.optString("interaction_id", "").trim();
            String subagentId = payload.optString("subagent_id", "").trim();

            if (manager == null) {
                resp.put("ok", false);
                resp.put("error", "NotificationManager unavailable");
                return resp;
            }

            int icon = context.getResources().getIdentifier("ic_notification", "drawable", context.getPackageName());
            if (icon == 0) {
                icon = android.R.drawable.ic_dialog_info;
            }

            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(context, channel)
                    : new Notification.Builder(context);

            builder.setContentTitle(title)
                    .setContentText(message)
                    .setSmallIcon(icon)
                    .setOngoing(ongoing)
                    .setAutoCancel(!ongoing);

            if (message.length() > 40) {
                builder.setStyle(new Notification.BigTextStyle().bigText(message));
            }

            if (indeterminate) {
                builder.setProgress(0, 0, true);
            } else if (progress >= 0) {
                builder.setProgress(max, Math.min(max, Math.max(0, progress)), false);
            }

            if (hasActions) {
                if (requestId.isEmpty()) {
                    requestId = "req_" + System.currentTimeMillis() + "_" + id;
                }
                ApprovalActionReceiver.registerPending(
                        context, requestId, title, message, cascadeId, interactionId, subagentId);

                int maxActions = Math.min(actions.length(), 3);
                for (int i = 0; i < maxActions; i++) {
                    JSONObject act = actions.optJSONObject(i);
                    if (act == null) {
                        continue;
                    }
                    String actId = act.optString("id", "").trim();
                    String actLabel = act.optString("label", actId).trim();
                    if (actId.isEmpty() || actLabel.isEmpty()) {
                        continue;
                    }

                    boolean isRemoteInput = act.optBoolean("remote_input", false)
                            || "remote_input".equalsIgnoreCase(act.optString("type", ""))
                            || ("deny_with_comment".equalsIgnoreCase(actId) && !act.has("remote_input"));
                    String replyLabel = act.optString("reply_label", defaultReplyLabel).trim();
                    if (replyLabel.isEmpty()) {
                        replyLabel = DEFAULT_REPLY_LABEL;
                    }

                    Intent actionIntent = new Intent(context, ApprovalActionReceiver.class);
                    actionIntent.setAction(ApprovalActionReceiver.ACTION_APPROVAL_DECISION);
                    actionIntent.putExtra(ApprovalActionReceiver.EXTRA_REQUEST_ID, requestId);
                    actionIntent.putExtra(ApprovalActionReceiver.EXTRA_DECISION, actId);
                    actionIntent.putExtra(ApprovalActionReceiver.EXTRA_NOTIFICATION_TAG, tag);
                    actionIntent.putExtra(ApprovalActionReceiver.EXTRA_NOTIFICATION_ID, id);
                    if (!callbackUrl.isEmpty()) {
                        actionIntent.putExtra(ApprovalActionReceiver.EXTRA_CALLBACK_URL, callbackUrl);
                    }
                    if (!cascadeId.isEmpty()) {
                        actionIntent.putExtra(ApprovalActionReceiver.EXTRA_CASCADE_ID, cascadeId);
                    }
                    if (!interactionId.isEmpty()) {
                        actionIntent.putExtra(ApprovalActionReceiver.EXTRA_INTERACTION_ID, interactionId);
                    }
                    if (!subagentId.isEmpty()) {
                        actionIntent.putExtra(ApprovalActionReceiver.EXTRA_SUBAGENT_ID, subagentId);
                    }
                    if (actionType != null && !actionType.isEmpty()) {
                        actionIntent.putExtra(ApprovalActionReceiver.EXTRA_ACTION_TYPE, actionType);
                    }

                    // Android 12+ (API 31+): RemoteInput actions MUST be FLAG_MUTABLE so the OS
                    // can attach the user's inline reply text; plain actions remain FLAG_IMMUTABLE.
                    int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
                    if (isRemoteInput) {
                        if (Build.VERSION.SDK_INT >= 31) {
                            piFlags |= PendingIntent.FLAG_MUTABLE;
                        }
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            piFlags |= PendingIntent.FLAG_IMMUTABLE;
                        }
                    }

                    int reqCode = (requestId + ":" + actId + ":" + i).hashCode();
                    PendingIntent pi = PendingIntent.getBroadcast(context, reqCode, actionIntent, piFlags);
                    Notification.Action.Builder actionBuilder =
                            new Notification.Action.Builder(0, actLabel, pi);
                    if (isRemoteInput) {
                        RemoteInput remoteInput = new RemoteInput.Builder(
                                ApprovalActionReceiver.KEY_APPROVAL_COMMENT)
                                .setLabel(replyLabel)
                                .build();
                        actionBuilder.addRemoteInput(remoteInput);
                    }
                    builder.addAction(actionBuilder.build());
                }
            }

            manager.notify(tag, id, builder.build());

            resp.put("ok", true);
            resp.put("tag", tag);
            resp.put("id", id);
            resp.put("channel", channel);
            if (!requestId.isEmpty()) {
                resp.put("request_id", requestId);
            }
        } catch (Exception e) {
            Log.w(TAG, "Notification error: " + e.getMessage());
            try {
                resp.put("ok", false);
                resp.put("error", e.getMessage());
            } catch (Exception ignored) {
            }
        }
        return resp;
    }

    private static JSONArray buildDefaultToolApprovalActions(String replyLabel) {
        JSONArray actions = new JSONArray();
        try {
            JSONObject approve = new JSONObject();
            approve.put("id", "approve");
            approve.put("label", "Approve");
            actions.put(approve);

            JSONObject deny = new JSONObject();
            deny.put("id", "deny");
            deny.put("label", "Deny");
            actions.put(deny);

            JSONObject denyComment = new JSONObject();
            denyComment.put("id", "deny_with_comment");
            denyComment.put("label", "Deny w/ Comment");
            denyComment.put("remote_input", true);
            denyComment.put("reply_label", replyLabel);
            actions.put(denyComment);
        } catch (Exception ignored) {
        }
        return actions;
    }

    private static String sanitizeTag(String raw) {
        StringBuilder sb = new StringBuilder();
        int limit = Math.min(raw.length(), 48);
        for (int i = 0; i < limit; i++) {
            char c = raw.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.length() > 0 ? sb.toString() : "default";
    }
}
