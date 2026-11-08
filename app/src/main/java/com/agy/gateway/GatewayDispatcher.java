package com.agy.gateway;

import android.content.Context;
import com.agy.BuildConfig;
import com.agy.core.CoreRuntimeState;
import com.agy.gateway.handlers.*;
import com.agy.log.CrashRecorder;
import com.agy.log.LogRingBuffer;
import org.json.JSONObject;

import java.io.File;

/**
 * Dispatches incoming JSON commands from the environment layer to specialized handlers.
 */
public final class GatewayDispatcher {

    private final NotificationHandler notificationHandler;
    private final BrowserHandler browserHandler;
    private final PowerHandler powerHandler;
    private final ClipboardHandler clipboardHandler;
    private final DeviceHandler deviceHandler;
    private final DoctorHandler doctorHandler;
    private final LogRingBuffer logBuffer;
    private final com.agy.log.CommandHistory commandHistory;
    private final ExecHandler execHandler;

    public GatewayDispatcher(Context context, LogRingBuffer logBuffer) {
        this.notificationHandler = new NotificationHandler(context);
        this.browserHandler = new BrowserHandler(context);
        this.powerHandler = new PowerHandler(context);
        this.clipboardHandler = new ClipboardHandler(context);
        this.deviceHandler = new DeviceHandler(context);
        this.doctorHandler = new DoctorHandler(context);
        this.logBuffer = logBuffer;
        this.commandHistory = new com.agy.log.CommandHistory();
        this.execHandler = new ExecHandler(context, this.commandHistory, logBuffer);
    }

    public JSONObject dispatch(String command, JSONObject payload) {
        if (payload == null) {
            payload = new JSONObject();
        }

        switch (command) {
            case "notify":
                return notificationHandler.handle(payload);
            case "open_url":
                return browserHandler.handle(payload);
            case "wakelock":
                return powerHandler.handle(payload);
            case "clipboard":
            case "clipboard_set":
                return clipboardHandler.handle(payload);
            case "system_info":
                return deviceHandler.handleSystemInfo();
            case "vibrate":
                return deviceHandler.handleVibrate(payload);
            case "toast":
                return deviceHandler.handleToast(payload);
            case "logs":
                JSONObject logsResp = new JSONObject();
                try {
                    int tail = payload.optInt("tail", 50);
                    logsResp.put("ok", true);
                    logsResp.put("logs", LogRingBuffer.toJsonArray(logBuffer.getRecent(tail)));
                } catch (Exception ignored) {}
                return logsResp;
            case "exec":
                return execHandler.handle(payload);
            case "exec_start":
                return execHandler.start(payload);
            case "exec_status":
            case "exec_poll":
                return execHandler.status(payload);
            case "exec_cancel":
                return execHandler.cancel(payload);
            case "exec_list":
            case "tasks":
                return execHandler.list(payload);
            case "commands":
                JSONObject cmdResp = new JSONObject();
                try {
                    int limit = payload.optInt("limit", 20);
                    cmdResp.put("ok", true);
                    cmdResp.put("commands", com.agy.log.CommandHistory.toJsonArray(commandHistory.getRecent(limit)));
                } catch (Exception ignored) {}
                return cmdResp;
            case "command_get":
                JSONObject singleResp = new JSONObject();
                try {
                    String id = payload.optString("id", "");
                    com.agy.log.CommandRecord rec = commandHistory.getById(id);
                    singleResp.put("ok", rec != null);
                    singleResp.put("command", rec != null ? rec.toJson() : JSONObject.NULL);
                } catch (Exception ignored) {}
                return singleResp;
            case "crash":
                JSONObject crashResp = new JSONObject();
                try {
                    JSONObject latest = CrashRecorder.getLatestCrash();
                    crashResp.put("ok", true);
                    crashResp.put("crash", latest != null ? latest : JSONObject.NULL);
                } catch (Exception ignored) {}
                return crashResp;
            case "status":
            case "health":
                JSONObject st = new JSONObject();
                try {
                    st.put("ok", true);
                    st.put("status", "online");
                    try { st.put("app_version", BuildConfig.APP_VERSION_NAME); } catch (Throwable ignored) {}
                    boolean ready = CoreRuntimeState.serverUrl != null && !CoreRuntimeState.serverUrl.trim().isEmpty();
                    JSONObject core = new JSONObject();
                    core.put("ready", ready);
                    core.put("restarting", CoreRuntimeState.restarting);
                    core.put("last_error", CoreRuntimeState.lastError == null ? JSONObject.NULL : CoreRuntimeState.lastError);
                    core.put("ready_generation", CoreRuntimeState.readyGeneration);
                    st.put("core", core);
                } catch (Exception ignored) {}
                return st;
            case "doctor":
                return doctorHandler.doctor();
            case "repair":
                return doctorHandler.repair(payload);
            default:
                JSONObject err = new JSONObject();
                try {
                    err.put("ok", false);
                    err.put("error", "unknown command: " + command);
                } catch (Exception ignored) {}
                return err;
        }
    }

    public com.agy.log.CommandHistory getCommandHistory() {
        return commandHistory;
    }

    public void releaseResources() {
        powerHandler.releaseAll();
    }
}
