package com.agy.core;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import com.agy.runtime.RuntimeManager;

import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * Foreground host for the patched core (language_server). Holds a wake lock,
 * streams the process log to files/logs/core.log, and publishes serverUrl once
 * ready.
 */
public final class CoreServerService extends Service {

    private static final String TAG = "CoreServer";

    private RuntimeManager runtime;
    private WakeLockHolder wakeLock;
    private OpenUrlWatcher openUrlWatcher;
    private CoreProcessSupervisor supervisor;
    private com.agy.log.LogRingBuffer logBuffer;
    private com.agy.gateway.SystemGatewayServer gatewayServer;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed;

    @Override
    public void onCreate() {
        super.onCreate();
        CoreRuntimeState.serviceActive = true;
        runtime = new RuntimeManager(this);
        logBuffer = new com.agy.log.LogRingBuffer();
        gatewayServer = new com.agy.gateway.SystemGatewayServer(this, logBuffer, com.agy.BuildConfig.GATEWAY_PORT);
        gatewayServer.start();

        Log.i(TAG, "state paths: HOME=" + getFilesDir().getAbsolutePath()
                + " appData=" + runtime.getAppDataDir().getAbsolutePath()
                + " projects=" + runtime.getProjectsDir().getAbsolutePath()
                + " runtime=" + runtime.getRuntimeDir().getAbsolutePath());
        CoreRuntimeState.httpsPort = runtime.getHttpsPort();
        CoreRuntimeState.csrfToken = runtime.getCsrfToken();
        CoreRuntimeState.restarting = false;
        CoreRuntimeState.lastError = null;
        CoreRuntimeState.serverUrl = null;

        ForegroundNotification.start(this);
        wakeLock = new WakeLockHolder();
        wakeLock.acquire(this);

        openUrlWatcher = new OpenUrlWatcher(this);
        openUrlWatcher.start();

        supervisor = new CoreProcessSupervisor(this, runtime, openUrlWatcher, logBuffer,
                this::onSupervisorTerminated);
        supervisor.start();

        startBackgroundDaemon();
    }

    private void startBackgroundDaemon() {
        new Thread(() -> {
            // Env install (phase 2) can take longer on first run; retry until the
            // daemon binary appears or the service is destroyed.
            final int maxAttempts = 20;
            final long delayMs = 3000;
            for (int attempt = 1; attempt <= maxAttempts && CoreRuntimeState.serviceActive && !destroyed; attempt++) {
                try {
                    Thread.sleep(attempt == 1 ? 1500 : delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                File daemonBin = new File(getFilesDir(), "usr/bin/agy-daemon");
                if (!daemonBin.isFile()) {
                    continue;
                }
                if (!daemonBin.canExecute() && !daemonBin.setExecutable(true, false)) {
                    Log.w(TAG, "agy-daemon exists but is not executable");
                    continue;
                }
                try {
                    Log.i(TAG, "Starting autonomous background daemon (attempt " + attempt + ")...");
                    ProcessBuilder pb = new ProcessBuilder(daemonBin.getAbsolutePath(), "start");
                    pb.directory(getFilesDir());
                    pb.environment().put("PATH", getFilesDir().getAbsolutePath() + "/usr/bin:" + System.getenv("PATH"));
                    pb.environment().put("HOME", getFilesDir().getAbsolutePath());
                    pb.start();
                    return;
                } catch (Exception e) {
                    Log.w(TAG, "Failed to start agy-daemon (attempt " + attempt + "): " + e.getMessage());
                }
            }
            if (CoreRuntimeState.serviceActive && !destroyed) {
                Log.w(TAG, "agy-daemon not started: binary missing or failed after retries");
            }
        }, "AgyDaemonStarter").start();
    }

    private void stopBackgroundDaemon() {
        try {
            File daemonBin = new File(getFilesDir(), "usr/bin/agy-daemon");
            if (daemonBin.canExecute()) {
                Log.i(TAG, "Stopping autonomous background daemon...");
                ProcessBuilder pb = new ProcessBuilder(daemonBin.getAbsolutePath(), "stop");
                pb.directory(getFilesDir());
                pb.environment().put("PATH", getFilesDir().getAbsolutePath() + "/usr/bin:" + System.getenv("PATH"));
                pb.environment().put("HOME", getFilesDir().getAbsolutePath());
                Process p = pb.start();
                if (!p.waitFor(1500, TimeUnit.MILLISECONDS)) {
                    Log.w(TAG, "agy-daemon stop timed out after 1500ms; destroying forcibly");
                    p.destroyForcibly();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to stop agy-daemon: " + e.getMessage());
        }
    }

    private void onSupervisorTerminated() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed) {
                    return;
                }
                CoreRuntimeState.serverUrl = null;
                CoreRuntimeState.restarting = false;
                if (CoreRuntimeState.lastError == null) {
                    CoreRuntimeState.lastError = "core supervisor stopped unexpectedly";
                }
                if (wakeLock != null) {
                    wakeLock.release();
                }
                if (runtime != null) {
                    runtime.stopNetworkMonitor();
                }
                if (openUrlWatcher != null) {
                    openUrlWatcher.stop();
                }
                ForegroundNotification.update(CoreServerService.this,
                        "Core stopped — open app to retry");
            }
        });
    }

    @Override
    public void onDestroy() {
        stopBackgroundDaemon();
        if (gatewayServer != null) {
            gatewayServer.stop();
        }
        destroyed = true;
        CoreRuntimeState.serviceActive = false;
        mainHandler.removeCallbacksAndMessages(null);
        // Intentional stop: the watchdog must not treat the exit as a crash.
        if (supervisor != null) {
            supervisor.stop();
        }
        if (openUrlWatcher != null) {
            openUrlWatcher.stop();
        }
        if (wakeLock != null) {
            wakeLock.release();
        }
        if (runtime != null) {
            runtime.stopNetworkMonitor();
        }
        CoreRuntimeState.serverUrl = null;
        CoreRuntimeState.lastError = null;
        CoreRuntimeState.restarting = false;
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
