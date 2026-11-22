package com.agy.core;

import android.content.Context;
import android.system.Os;
import android.text.TextUtils;
import android.util.Log;

import com.agy.gateway.SystemGatewayServer;
import com.agy.runtime.RuntimeManager;
import com.agy.util.CoreUrl;
import com.agy.util.ErrorText;
import com.agy.util.IoUtil;
import com.agy.util.ProcessUtils;
import com.agy.util.SensitiveDataRedactor;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

/**
 * Supervises the core for its whole life. One pass = install, launch, wait for
 * ready, then block until exit. Intentional stops end the loop; an unexpected
 * exit is auto-restarted with exponential backoff up to MAX_RESTARTS, the
 * budget refilling after SUSTAINED_READY_MS of uptime.
 */
public final class CoreProcessSupervisor {

    private static final String TAG = "CoreProcessSupervisor";
    public static final String CORE_STATE_REL_PATH = "logs/core.state.json";

    private final Context appContext;
    private final RuntimeManager runtime;
    private final OpenUrlWatcher openUrlWatcher;
    private final CoreEventWatcher coreEventWatcher;
    private final com.agy.log.LogRingBuffer logBuffer;
    private final Runnable terminalListener;

    private volatile Process process;
    private volatile boolean stopping = false;
    private Thread worker;

    public CoreProcessSupervisor(Context context, RuntimeManager runtime,
                                 OpenUrlWatcher openUrlWatcher,
                                 com.agy.log.LogRingBuffer logBuffer,
                                 Runnable terminalListener) {
        this(context, runtime, openUrlWatcher, new CoreEventWatcher(context), logBuffer, terminalListener);
    }

    public CoreProcessSupervisor(Context context, RuntimeManager runtime,
                                 OpenUrlWatcher openUrlWatcher,
                                 CoreEventWatcher coreEventWatcher,
                                 com.agy.log.LogRingBuffer logBuffer,
                                 Runnable terminalListener) {
        this.appContext = context.getApplicationContext();
        this.runtime = runtime;
        this.openUrlWatcher = openUrlWatcher;
        this.coreEventWatcher = coreEventWatcher != null ? coreEventWatcher : new CoreEventWatcher(this.appContext);
        this.logBuffer = logBuffer;
        this.terminalListener = terminalListener;
    }

    public void start() {
        stopping = false;
        worker = new Thread(this::runCore, "CoreServerThread");
        worker.start();
    }

    public void stop() {
        stopping = true;
        clearCoreStateFile(appContext.getFilesDir());
        destroy(process);
        if (worker != null) {
            worker.interrupt();
        }
    }

    private void runCore() {
        try {
            runCoreLoop();
        } finally {
            clearCoreStateFile(appContext.getFilesDir());
            if (!stopping && terminalListener != null) {
                try {
                    terminalListener.run();
                } catch (Exception e) {
                    Log.w(TAG, "terminal callback failed", e);
                }
            }
        }
    }

    private void runCoreLoop() {
        int attempt = 0;
        while (!stopping) {
            clearCoreStateFile(appContext.getFilesDir());
            Writer log = CoreLogWriter.open(appContext.getFilesDir());
            try {
                CoreRuntimeState.lastError = null;
                CoreRuntimeState.restarting = false;
                Process proc = CoreLauncher.launch(runtime, appContext.getFilesDir());
                process = proc;

                final Writer streamWriter = log;
                Thread streamer = new Thread(() -> streamLog(proc, streamWriter), "CoreStreamThread");
                streamer.setDaemon(true);
                streamer.start();

                if (!waitForReady(proc, log)) {
                    clearCoreStateFile(appContext.getFilesDir());
                    if (stopping) {
                        break;
                    }
                    // Ready timeout / launch death is fatal: no blind auto-restart.
                    if (CoreRuntimeState.lastError == null) {
                        CoreRuntimeState.lastError =
                                "core did not become ready within " + CoreReadyProbe.READY_TIMEOUT_MS + "ms";
                        CoreLogWriter.writeLine(log, CoreRuntimeState.lastError);
                    }
                    destroy(proc);
                    break;
                }

                long readyAt = System.currentTimeMillis();
                try {
                    proc.waitFor();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                clearCoreStateFile(appContext.getFilesDir());
                if (stopping) {
                    break;
                }

                int code = ProcessUtils.exitCode(proc);
                if (System.currentTimeMillis() - readyAt >= CoreRestartPolicy.SUSTAINED_READY_MS) {
                    attempt = 0;
                }
                CoreRuntimeState.serverUrl = null;
                attempt++;
                String tail = getLastLinesFromLog(appContext.getFilesDir(), 15);
                if (attempt > CoreRestartPolicy.MAX_RESTARTS) {
                    CoreRuntimeState.restarting = false;
                    CoreRuntimeState.lastError = "core crashed " + CoreRestartPolicy.MAX_RESTARTS
                            + " times; giving up (last exit code " + code + ")"
                            + (tail.isEmpty() ? "" : "\n--- core.log tail ---\n" + tail);
                    CoreLogWriter.writeLine(log, CoreRuntimeState.lastError);
                    com.agy.log.CrashRecorder.recordCrash(appContext, code, System.currentTimeMillis() - readyAt, CoreRuntimeState.lastError, logBuffer);
                    break;
                }
                long delay = CoreRestartPolicy.delayFor(attempt);
                CoreRuntimeState.restarting = true;
                CoreRuntimeState.lastError = "core exited unexpectedly (code " + code
                        + "); restarting in " + (delay / 1000) + "s (attempt "
                        + attempt + "/" + CoreRestartPolicy.MAX_RESTARTS + ")"
                        + (tail.isEmpty() ? "" : "\n--- core.log tail ---\n" + tail);
                Log.w(TAG, CoreRuntimeState.lastError);
                CoreLogWriter.writeLine(log, CoreRuntimeState.lastError);
                com.agy.log.CrashRecorder.recordCrash(appContext, code, System.currentTimeMillis() - readyAt, CoreRuntimeState.lastError, logBuffer);
                CoreLogWriter.closeQuietly(log);
                log = null;
                sleepInterruptible(delay);
            } catch (Exception e) {
                if (stopping) {
                    break;
                }
                Log.e(TAG, "core start failed", e);
                CoreRuntimeState.lastError = ErrorText.describe(e);
                CoreLogWriter.writeLine(log, "core start failed: " + CoreRuntimeState.lastError);
                // install/launch failure: fail-closed, user retries from the splash.
                break;
            } finally {
                CoreLogWriter.closeQuietly(log);
            }
        }
    }

    private boolean waitForReady(Process proc, Writer log) {
        long start = System.currentTimeMillis();
        String csrf = runtime.getCsrfToken();
        int port = CoreRuntimeState.httpsPort;
        while (System.currentTimeMillis() - start < CoreReadyProbe.READY_TIMEOUT_MS) {
            if (stopping) {
                return false;
            }
            if (!ProcessUtils.isAlive(proc)) {
                int code = ProcessUtils.exitCode(proc);
                ProcessUtils.sleep(200);
                String lastLogs = getLastLinesFromLog(appContext.getFilesDir(), 10);
                CoreRuntimeState.lastError = "core process exited before it became ready (exit code " + code + ")"
                        + (lastLogs.isEmpty() ? "" : "\n--- core.log tail ---\n" + lastLogs);
                CoreLogWriter.writeLine(log, CoreRuntimeState.lastError);
                return false;
            }
            if (CoreReadyProbe.probeOnce(appContext, port)) {
                long readyAt = System.currentTimeMillis();
                CoreRuntimeState.serverUrl = CoreUrl.startUrl(port, csrf);
                CoreRuntimeState.lastError = null;
                CoreRuntimeState.restarting = false;
                CoreRuntimeState.readyGeneration++;
                writeCoreStateFile(appContext.getFilesDir(), port, csrf,
                        SystemGatewayServer.getActivePort(), extractPid(proc), readyAt);
                String readyStatus = "core ready on 127.0.0.1:" + port;
                Log.i(TAG, readyStatus);
                CoreLogWriter.writeLine(log, readyStatus);
                return true;
            }
        }
        return false;
    }

    private void streamLog(Process proc, Writer log) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String safeLine = SensitiveDataRedactor.redactLine(line);
                Log.d(TAG, safeLine);
                if (logBuffer != null) {
                    logBuffer.appendLine(safeLine);
                }
                CoreLogWriter.writeLine(log, safeLine);
                openUrlWatcher.handleStreamLine(line);
                if (coreEventWatcher != null) {
                    coreEventWatcher.handleStreamLine(line);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "log stream ended", e);
        }
    }

    /** Atomically writes {@code files/logs/core.state.json} with strict 0600 permissions. */
    static void writeCoreStateFile(File filesDir, int httpsPort, String csrfToken,
                                   int gatewayPort, long pid, long readyAtMs) {
        if (filesDir == null) {
            return;
        }
        File target = new File(filesDir, CORE_STATE_REL_PATH);
        File dir = target.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "cannot create logs dir for core.state.json");
            return;
        }
        JSONObject state = new JSONObject();
        try {
            state.put("ready", true);
            state.put("https_port", httpsPort);
            state.put("csrf_token", csrfToken != null ? csrfToken : "");
            state.put("base_url", "https://127.0.0.1:" + httpsPort);
            state.put("gateway_port", gatewayPort);
            state.put("pid", pid);
            state.put("ready_at", readyAtMs);
        } catch (Exception e) {
            Log.w(TAG, "failed building core.state.json: " + e.getMessage());
            return;
        }

        File tmp = new File(dir, target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            try {
                Os.chmod(tmp.getAbsolutePath(), 0600);
            } catch (Throwable ignored) {
            }
            tmp.setReadable(false, false);
            tmp.setReadable(true, true);
            tmp.setWritable(false, false);
            tmp.setWritable(true, true);
            out.write((state.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } catch (Exception e) {
            Log.w(TAG, "failed writing core.state.json tmp: " + e.getMessage());
            IoUtil.deleteQuietly(tmp);
            return;
        }
        if (!tmp.renameTo(target)) {
            if (!(target.delete() && tmp.renameTo(target))) {
                Log.w(TAG, "failed renaming core.state.json tmp into place");
                IoUtil.deleteQuietly(tmp);
                return;
            }
        }
        try {
            Os.chmod(target.getAbsolutePath(), 0600);
        } catch (Throwable ignored) {
        }
    }

    /** Removes {@code files/logs/core.state.json} when the core is stopped or crashed. */
    public static void clearCoreStateFile(File filesDir) {
        if (filesDir == null) {
            return;
        }
        File target = new File(filesDir, CORE_STATE_REL_PATH);
        if (target.exists() && !target.delete()) {
            Log.w(TAG, "failed to remove stale core.state.json");
        }
    }

    private static long extractPid(Process proc) {
        if (proc == null) {
            return -1L;
        }
        Class<?> cls = proc.getClass();
        while (cls != null) {
            try {
                Field f = cls.getDeclaredField("pid");
                f.setAccessible(true);
                Object v = f.get(proc);
                if (v instanceof Number) {
                    return ((Number) v).longValue();
                }
            } catch (Throwable ignored) {
            }
            cls = cls.getSuperclass();
        }
        return -1L;
    }

    private void sleepInterruptible(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (!stopping) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) {
                return;
            }
            ProcessUtils.sleep(Math.min(left, 250));
        }
    }

    private static void destroy(Process proc) {
        if (proc != null) {
            try {
                proc.destroy();
            } catch (Exception ignored) {
            }
            try {
                proc.destroyForcibly();
            } catch (Exception ignored) {
            }
        }
        CoreLauncher.killOrphanCoreProcesses();
    }

    private static String getLastLinesFromLog(java.io.File filesDir, int maxLines) {
        java.io.File logFile = new java.io.File(filesDir, "logs/core.log");
        if (!logFile.isFile()) {
            return "";
        }
        java.util.List<String> lines = new java.util.ArrayList<>();
        try (BufferedReader br = new BufferedReader(new java.io.FileReader(logFile))) {
            String l;
            while ((l = br.readLine()) != null) {
                lines.add(SensitiveDataRedactor.redactLine(l));
                if (lines.size() > maxLines) {
                    lines.remove(0);
                }
            }
        } catch (Exception ignored) {
        }
        return TextUtils.join("\n", lines);
    }
}
