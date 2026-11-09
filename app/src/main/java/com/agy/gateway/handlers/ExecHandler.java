package com.agy.gateway.handlers;

import android.content.Context;
import android.util.Log;

import com.agy.BuildConfig;
import com.agy.log.CommandHistory;
import com.agy.log.CommandRecord;
import com.agy.log.LogRingBuffer;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles command execution with optional async mode, incremental polling, and cancellation.
 *
 * Commands (via GatewayDispatcher): exec (sync default; async when payload.async=true),
 * exec_start, exec_status, exec_cancel.
 */
public final class ExecHandler {

    private static final String TAG = "ExecHandler";
    private static final int MAX_OUTPUT_CHARS = 256 * 1024; // 256 KB per stream
    private static final long TASK_RETENTION_MS = 10 * 60 * 1000L;
    private static final int MAX_RETAINED_TASKS = 128;

    private final Context context;
    private final CommandHistory history;
    private final LogRingBuffer logBuffer;
    private final ConcurrentHashMap<String, ExecTask> tasks = new ConcurrentHashMap<>();

    public ExecHandler(Context context, CommandHistory history, LogRingBuffer logBuffer) {
        this.context = context.getApplicationContext();
        this.history = history;
        this.logBuffer = logBuffer;
    }

    /** Synchronous exec for backward compatibility; supports payload.async=true. */
    public JSONObject handle(JSONObject payload) {
        if (payload != null && payload.optBoolean("async", false)) {
            return start(payload);
        }
        ExecSpec spec = ExecSpec.from(payload, context);
        JSONObject err = spec.validate();
        if (err != null) return err;
        ExecTask task = new ExecTask(spec, history, logBuffer, this);
        task.runBlocking();
        return task.toResultJson(0, 0, true);
    }

    public JSONObject start(JSONObject payload) {
        cleanupTasks();
        ExecSpec spec = ExecSpec.from(payload, context);
        JSONObject err = spec.validate();
        if (err != null) return err;
        ExecTask task = new ExecTask(spec, history, logBuffer, this);
        tasks.put(task.id, task);
        Thread t = new Thread(task::runBlocking, "ExecTask-" + task.id);
        t.setDaemon(true);
        t.start();
        JSONObject resp = new JSONObject();
        try {
            resp.put("ok", true);
            resp.put("id", task.id);
            resp.put("status", "RUNNING");
            resp.put("async", true);
        } catch (Exception ignored) {}
        return resp;
    }

    public JSONObject status(JSONObject payload) {
        cleanupTasks();
        String id = payload != null ? payload.optString("id", "").trim() : "";
        int outOff = payload != null ? Math.max(0, payload.optInt("stdout_offset", 0)) : 0;
        int errOff = payload != null ? Math.max(0, payload.optInt("stderr_offset", 0)) : 0;
        boolean includeOutput = payload == null || payload.optBoolean("include_output", true);
        ExecTask task = tasks.get(id);
        if (task == null) {
            if (history != null) {
                CommandRecord rec = history.getById(id);
                if (rec != null) {
                    JSONObject resp = rec.toJson();
                    try {
                        resp.put("ok", true);
                        resp.put("found", true);
                        resp.put("running", false);
                    } catch (Exception ignored) {}
                    return resp;
                }
            }
            JSONObject err = new JSONObject();
            try {
                err.put("ok", false);
                err.put("error", "unknown id");
                err.put("id", id);
            } catch (Exception ignored) {}
            return err;
        }
        return task.toResultJson(outOff, errOff, includeOutput);
    }

    public JSONObject cancel(JSONObject payload) {
        String id = payload != null ? payload.optString("id", "").trim() : "";
        ExecTask task = tasks.get(id);
        if (task == null) {
            JSONObject err = new JSONObject();
            try {
                err.put("ok", false);
                err.put("error", "unknown id");
                err.put("id", id);
            } catch (Exception ignored) {}
            return err;
        }
        task.cancel();
        JSONObject resp = new JSONObject();
        try {
            resp.put("ok", true);
            resp.put("id", id);
            resp.put("status", task.getStatus());
        } catch (Exception ignored) {}
        return resp;
    }

    public JSONObject list(JSONObject payload) {
        cleanupTasks();
        boolean activeOnly = payload != null && payload.optBoolean("active_only", false);
        int limit = payload != null ? Math.max(1, Math.min(payload.optInt("limit", 20), 100)) : 20;
        org.json.JSONArray arr = new org.json.JSONArray();
        try {
            int n = 0;
            for (ExecTask t : tasks.values()) {
                if (n >= limit) break;
                if (activeOnly && t.isTerminal()) continue;
                JSONObject o = new JSONObject();
                o.put("id", t.id);
                o.put("status", t.getStatus());
                o.put("running", !t.isTerminal());
                o.put("cmd", t.cmdText());
                o.put("duration_ms", t.durationMs());
                arr.put(o);
                n++;
            }
        } catch (Exception ignored) {}
        JSONObject resp = new JSONObject();
        try { resp.put("ok", true); resp.put("tasks", arr); } catch (Exception ignored) {}
        return resp;
    }

    private void cleanupTasks() {
        long now = System.currentTimeMillis();
        if (tasks.size() > MAX_RETAINED_TASKS) {
            // Remove oldest terminal tasks first.
            Iterator<Map.Entry<String, ExecTask>> it = tasks.entrySet().iterator();
            while (it.hasNext() && tasks.size() > MAX_RETAINED_TASKS) {
                ExecTask t = it.next().getValue();
                if (t.isTerminal() && (now - t.finishedAt) > 60_000L) it.remove();
            }
        }
        Iterator<Map.Entry<String, ExecTask>> it = tasks.entrySet().iterator();
        while (it.hasNext()) {
            ExecTask t = it.next().getValue();
            if (t.isTerminal() && t.finishedAt > 0 && (now - t.finishedAt) > TASK_RETENTION_MS) {
                it.remove();
            }
        }
    }

    private static final class ExecSpec {
        final String cmd;
        final String source;
        final long timeoutMs;
        final boolean useRish;
        final String cwdPath;

        private ExecSpec(String cmd, String source, long timeoutMs, boolean useRish, String cwdPath) {
            this.cmd = cmd; this.source = source; this.timeoutMs = timeoutMs; this.useRish = useRish; this.cwdPath = cwdPath;
        }

        static ExecSpec from(JSONObject payload, Context context) {
            if (payload == null) payload = new JSONObject();
            return new ExecSpec(
                    payload.optString("cmd", "").trim(),
                    payload.optString("source", "agent"),
                    payload.optLong("timeout_ms", 30000),
                    payload.optBoolean("rish", false),
                    payload.optString("cwd", context.getFilesDir().getAbsolutePath()));
        }

        JSONObject validate() {
            if (cmd.isEmpty()) {
                JSONObject err = new JSONObject();
                try { err.put("ok", false); err.put("error", "empty command"); } catch (Exception ignored) {}
                return err;
            }
            return null;
        }
    }

    private final class ExecTask {
        final String id = "cmd_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 4);
        private final ExecSpec spec;
        private final CommandHistory taskHistory;
        private final LogRingBuffer taskLogBuffer;
        private final ExecHandler owner;
        private final long startTime = System.currentTimeMillis();
        private final Object lock = new Object();
        private final StringBuilder stdoutBuf = new StringBuilder();
        private final StringBuilder stderrBuf = new StringBuilder();
        private volatile Process process;
        private volatile String status = "RUNNING";
        private volatile int exitCode = -1;
        private volatile long finishedAt = 0;
        private volatile boolean cancelled = false;

        ExecTask(ExecSpec spec, CommandHistory history, LogRingBuffer logBuffer, ExecHandler owner) {
            this.spec = spec; this.taskHistory = history; this.taskLogBuffer = logBuffer; this.owner = owner;
        }

        String getStatus() { return status; }
        boolean isTerminal() { return !"RUNNING".equals(status) && !"CANCELLING".equals(status); }
        String cmdText() { return spec.cmd; }
        long durationMs() { return Math.max(0, (finishedAt > 0 ? finishedAt : System.currentTimeMillis()) - startTime); }

        void runBlocking() {
            if (BuildConfig.DEBUG) {
                String startMsg = String.format("[EXEC] START [%s] (source=%s): %s", id, spec.source, spec.cmd);
                Log.d(TAG, startMsg);
                if (taskLogBuffer != null) taskLogBuffer.append("exec", "DEBUG", startMsg);
            }
            Thread outThread = null;
            Thread errThread = null;
            try {
                ProcessBuilder pb;
                if (spec.useRish) {
                    pb = new ProcessBuilder(resolveRishPath(), "-c", spec.cmd);
                } else {
                    pb = new ProcessBuilder("/system/bin/sh", "-c", spec.cmd);
                }
                File cwd = new File(spec.cwdPath);
                if (cwd.isDirectory()) pb.directory(cwd);
                String home = context.getFilesDir().getAbsolutePath();
                pb.environment().put("HOME", home);
                pb.environment().put("PATH", home + "/usr/bin:" + home + "/runtime/bin:/system/bin:" + System.getenv("PATH"));
                pb.environment().put("RISH_APPLICATION_ID", context.getPackageName());
                pb.environment().remove("LD_LIBRARY_PATH");

                process = pb.start();
                try { process.getOutputStream().close(); } catch (Exception ignored) {}
                outThread = new Thread(() -> drainStream(process.getInputStream(), stdoutBuf), "StdoutDrain-" + id);
                errThread = new Thread(() -> drainStream(process.getErrorStream(), stderrBuf), "StderrDrain-" + id);
                outThread.setDaemon(true); errThread.setDaemon(true);
                outThread.start(); errThread.start();

                boolean finished = process.waitFor(spec.timeoutMs, TimeUnit.MILLISECONDS);
                if (cancelled) {
                    finish(cancelledStatus(), -1, outThread, errThread);
                } else if (finished) {
                    exitCode = process.exitValue();
                    joinQuietly(outThread, 800);
                    joinQuietly(errThread, 800);
                    status = (exitCode == 0) ? "SUCCESS" : "FAILED";
                } else {
                    terminateProcess(process);
                    interruptQuietly(outThread); interruptQuietly(errThread);
                    joinQuietly(outThread, 300); joinQuietly(errThread, 300);
                    appendCapped(stderrBuf, "\n[Process timed out after " + spec.timeoutMs + "ms]");
                    status = "TIMEOUT";
                }
            } catch (Exception e) {
                if (cancelled) {
                    status = "CANCELLED";
                } else {
                    status = "ERROR";
                    appendCapped(stderrBuf, "\nExecution exception: " + e.getMessage());
                    Log.w(TAG, "exec error: " + e.getMessage());
                }
                interruptQuietly(outThread); interruptQuietly(errThread);
            } finally {
                finishedAt = System.currentTimeMillis();
                recordResult();
            }
        }

        private void finish(String st, int code, Thread outThread, Thread errThread) {
            terminateProcess(process);
            interruptQuietly(outThread); interruptQuietly(errThread);
            joinQuietly(outThread, 300); joinQuietly(errThread, 300);
            status = st;
            exitCode = code;
        }

        void cancel() {
            if (isTerminal()) return;
            cancelled = true;
            status = "CANCELLING";
            terminateProcess(process);
        }

        private String cancelledStatus() { return "CANCELLED"; }

        private void recordResult() {
            long durationMs = Math.max(0, finishedAt - startTime);
            String stdoutStr; String stderrStr;
            synchronized (lock) {
                stdoutStr = stdoutBuf.toString().trim();
                stderrStr = stderrBuf.toString().trim();
            }
            CommandRecord record = new CommandRecord(id, startTime, spec.cmd, exitCode, durationMs, stdoutStr, stderrStr, status, spec.source);
            if (taskHistory != null) taskHistory.record(record);
            if (BuildConfig.DEBUG) {
                String endMsg = String.format("[EXEC] DONE [%s] (exit=%d, %dms, status=%s)", id, exitCode, durationMs, status);
                Log.d(TAG, endMsg);
                if (taskLogBuffer != null) {
                    taskLogBuffer.append("exec", (exitCode == 0 ? "INFO" : "WARN"), endMsg);
                    if (!stderrStr.isEmpty()) taskLogBuffer.append("exec", "WARN", "[stderr] " + stderrStr);
                }
            }
        }

        JSONObject toResultJson(int stdoutOffset, int stderrOffset, boolean includeOutput) {
            JSONObject resp = new JSONObject();
            long now = System.currentTimeMillis();
            long durationMs = Math.max(0, (finishedAt > 0 ? finishedAt : now) - startTime);
            try {
                resp.put("ok", true);
                resp.put("id", id);
                resp.put("status", status);
                resp.put("running", !isTerminal());
                resp.put("exit_code", exitCode);
                resp.put("duration_ms", durationMs);
                resp.put("cmd", spec.cmd);
                resp.put("source", spec.source);
                if (includeOutput) {
                    synchronized (lock) {
                        String out = stdoutBuf.toString();
                        String err = stderrBuf.toString();
                        resp.put("stdout", slice(out, stdoutOffset));
                        resp.put("stderr", slice(err, stderrOffset));
                        resp.put("stdout_total", out.length());
                        resp.put("stderr_total", err.length());
                        resp.put("stdout_offset", Math.min(stdoutOffset, out.length()));
                        resp.put("stderr_offset", Math.min(stderrOffset, err.length()));
                    }
                }
            } catch (Exception ignored) {}
            return resp;
        }

        private void drainStream(InputStream in, StringBuilder out) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                char[] buf = new char[4096];
                int n;
                boolean truncated = false;
                while ((n = reader.read(buf)) != -1) {
                    synchronized (lock) {
                        if (out.length() >= MAX_OUTPUT_CHARS) {
                            truncated = true;
                        } else {
                            int remaining = MAX_OUTPUT_CHARS - out.length();
                            out.append(buf, 0, Math.min(n, remaining));
                            if (n > remaining) truncated = true;
                        }
                    }
                    if (truncated && out.length() >= MAX_OUTPUT_CHARS) {
                        // keep draining to avoid blocking the process
                    }
                }
                if (truncated) appendCapped(out, "\n[output truncated]");
            } catch (Exception ignored) {}
        }

        private void appendCapped(StringBuilder out, String text) {
            if (text == null) return;
            synchronized (lock) {
                int remaining = MAX_OUTPUT_CHARS - out.length();
                if (remaining <= 0) return;
                if (text.length() <= remaining) out.append(text);
                else { out.append(text, 0, remaining); out.append("\n[output truncated]"); }
            }
        }
    }

    private static String slice(String s, int offset) {
        if (s == null) return "";
        if (offset <= 0) return s;
        if (offset >= s.length()) return "";
        return s.substring(offset);
    }

    private static void terminateProcess(Process proc) {
        if (proc == null) return;
        try { proc.destroy(); } catch (Exception ignored) {}
        try {
            if (!proc.waitFor(500, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly();
                proc.waitFor(500, TimeUnit.MILLISECONDS);
            }
        } catch (Exception ignored) {
            try { proc.destroyForcibly(); } catch (Exception ignored2) {}
        }
        try { if (proc.getInputStream() != null) proc.getInputStream().close(); } catch (Exception ignored) {}
        try { if (proc.getErrorStream() != null) proc.getErrorStream().close(); } catch (Exception ignored) {}
        try { if (proc.getOutputStream() != null) proc.getOutputStream().close(); } catch (Exception ignored) {}
    }

    private static void joinQuietly(Thread t, long ms) {
        if (t == null) return;
        try { t.join(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }

    private static void interruptQuietly(Thread t) {
        if (t == null) return;
        try { t.interrupt(); } catch (Exception ignored) {}
    }

    private String resolveRishPath() {
        File files = context.getFilesDir();
        File[] candidates = new File[]{
                new File(files, "usr/bin/rish"),
                new File(files, "runtime/bin/rish"),
                new File(files, "bin/rish")
        };
        for (File candidate : candidates) {
            if (candidate.exists() && candidate.canExecute()) {
                return candidate.getAbsolutePath();
            }
        }
        for (File candidate : candidates) {
            if (candidate.exists()) {
                return candidate.getAbsolutePath();
            }
        }
        return new File(files, "usr/bin/rish").getAbsolutePath();
    }
}
