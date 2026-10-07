package com.agy.runtime;

import android.util.Log;

import com.agy.install.EnvInstaller;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Phase 2: extract the bionic toolchain and regenerate runtime/bin bridges on a
 * daemon thread, in parallel with core startup. Best-effort; a failed attempt
 * clears the guard so a later retry can run again.
 */
public final class EnvInstallScheduler {

    private static final String TAG = "EnvInstallScheduler";

    private EnvInstallScheduler() {
    }

    public static void schedule(final RuntimeManager rm, final AtomicBoolean started) {
        if (!started.compareAndSet(false, true)) {
            Log.i(TAG, "env install already started; skipping duplicate");
            return;
        }
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean fresh = EnvInstaller.install(rm.appContext());
                    // The core is glibc and must not inherit bionic's LD_* vars,
                    // so give it #!/system/bin/sh bridges in runtime/bin that set
                    // the bionic env for the child only, then exec $PREFIX/bin/<tool>.
                    // A fresh env install changes the tool set -> force the bulk pass.
                    ShellBridgeInstaller.install(rm, fresh);
                    Log.i(TAG, "env toolchain ready; runtime/bin bridges refreshed");
                } catch (Throwable e) {
                    started.set(false);
                    Log.e(TAG, "env install failed; core continues without bionic tools: "
                            + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
                }
            }
        }, "EnvInstallThread");
        thread.setDaemon(true);
        thread.start();
    }
}
