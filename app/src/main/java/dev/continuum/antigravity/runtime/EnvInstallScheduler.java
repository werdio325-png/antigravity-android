package dev.continuum.antigravity.runtime;

import android.util.Log;

import dev.continuum.antigravity.install.EnvBundleManifest;
import dev.continuum.antigravity.install.EnvInstaller;
import dev.continuum.antigravity.install.SeedPackageInstaller;

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
                    EnvBundleManifest manifest = EnvBundleManifest.load(rm.appContext().getAssets());
                    boolean seeded = SeedPackageInstaller.installDefaults(
                            rm.appContext(), EnvInstaller.prefix(rm.appContext()), manifest.seedPackages);
                    // The core is glibc and must not inherit bionic's LD_* vars,
                    // so bridges set the bionic env for each child only. Seeded
                    // packages can add commands, so refresh the bridge inventory after them.
                    ShellBridgeInstaller.install(rm, fresh || seeded);
                    Log.i(TAG, "env ready; bundle=" + manifest.bundleId
                            + " seeded=" + seeded + "; runtime/bin bridges refreshed");
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
