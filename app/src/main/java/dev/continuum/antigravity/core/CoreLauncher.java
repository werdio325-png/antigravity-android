package dev.continuum.antigravity.core;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import dev.continuum.antigravity.runtime.RuntimeManager;
import dev.continuum.antigravity.util.ProcessUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/** Installs (phase 1 + phase 2) then launches the patched core process. */
public final class CoreLauncher {

    private static final String TAG = "CoreLauncher";

    private CoreLauncher() {
    }

    /** Terminate any leftover orphan core processes from previous sessions to prevent EADDRINUSE on fixed ports. */
    public static void killOrphanCoreProcesses() {
        int myPid = android.os.Process.myPid();
        File procDir = new File("/proc");
        File[] entries = procDir.listFiles();
        if (entries == null) {
            return;
        }
        boolean killedAny = false;
        for (File entry : entries) {
            String name = entry.getName();
            if (!name.matches("\\d+")) {
                continue;
            }
            try {
                int pid = Integer.parseInt(name);
                if (pid == myPid) {
                    continue;
                }
                File cmdlineFile = new File(entry, "cmdline");
                if (!cmdlineFile.canRead()) {
                    continue;
                }
                byte[] buf = new byte[512];
                int n;
                try (FileInputStream fis = new FileInputStream(cmdlineFile)) {
                    n = fis.read(buf);
                }
                if (n > 0) {
                    String cmdline = new String(buf, 0, n);
                    if (cmdline.contains("language_server") || cmdline.contains("liblanguage_server")) {
                        Log.w(TAG, "Terminating orphan core process (PID " + pid + ")");
                        android.os.Process.sendSignal(pid, android.os.Process.SIGNAL_KILL);
                        killedAny = true;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (killedAny) {
            ProcessUtils.sleep(200);
        }
    }

    public static Process launch(RuntimeManager runtime, File filesDir) throws IOException {
        // Pre-flight: kill any zombie/orphan core instances to release the fixed loopback port
        killOrphanCoreProcesses();

        // Phase 1 only: extract + verify the core (fail-closed, throws on
        // corruption) and return. Never wait on the ~90 MB bionic env here.
        runtime.installCore();
        // Phase 2: env toolchain + bridges on a daemon thread, deferred
        // by 3500ms to avoid peak disk I/O throttling during startup.
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                runtime.installEnvInBackground();
            }
        }, 3500);
        ProcessBuilder pb = new ProcessBuilder(runtime.coreCommand());
        pb.directory(filesDir);
        pb.redirectErrorStream(true);
        runtime.applyEnvironment(pb);
        return pb.start();
    }
}
