package com.agy.runtime;

import android.content.Context;
import android.util.Log;

import com.agy.install.EnvInstaller;
import com.agy.util.AssetUnpacker;
import com.agy.util.FileModeUtil;

import java.io.File;
import java.io.IOException;

/** Phase 1: extract + verify the core runtime and the web UI (fail-closed). */
public final class CoreInstaller {

    private static final String TAG = "CoreInstaller";

    private CoreInstaller() {
    }

    public static void installCore(RuntimeManager rm) {
        Context ctx = rm.appContext();
        File runtimeDir = rm.getRuntimeDir();
        boolean extracted = isCoreExtracted(rm);
        boolean verified = false;
        if (extracted && runtimeMarkerFresh(rm)) {
            // Verified when the APK was last updated; it has not changed since, so
            // re-hashing ~174MB of runtime on every launch is redundant.
            verified = true;
            Log.i(TAG, "runtime already extracted and verified (APK unchanged); skipping extract");
        } else {
            String manifestError = ManifestVerifier.verifyError(ctx, runtimeDir);
            if (extracted && manifestError == null) {
                verified = true;
                refreshRuntimeMarker(rm);
                Log.i(TAG, "runtime already extracted and verified; skipping extract");
            }
        }
        if (!verified) {
            Log.i(TAG, "extracting assets/runtime (first launch or re-install)");
            try {
                wipe(rm, runtimeDir);
                AssetUnpacker.unpackTree(ctx.getAssets(), RuntimePaths.RUNTIME_ASSET, runtimeDir);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "cannot unpack assets/runtime into " + runtimeDir
                                + ": " + e.getMessage(), e);
            }
            FileModeUtil.recursiveSetReadableExecutable(rm.getBinDir());
            String manifestError = ManifestVerifier.verifyError(ctx, runtimeDir);
            if (manifestError != null) {
                throw new IllegalStateException(
                        "runtime integrity check failed: " + manifestError);
            }
            writeMarker(rm.getRuntimeDir(), RuntimePaths.RUNTIME_MARKER);
        }
        if (!isWebExtracted(rm)) {
            try {
                AssetUnpacker.unpackTree(ctx.getAssets(), RuntimePaths.WEB_ASSET, rm.getWebDir());
                writeMarker(rm.getWebDir(), RuntimePaths.WEB_MARKER);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "cannot unpack assets/web into " + rm.getWebDir()
                                + ": " + e.getMessage(), e);
            }
        }
        // Exec bits are set once at extraction (and on every generated bridge), so
        // no recursive chmod is needed on a warm launch.
        rm.writeNetworkConfig();
        rm.startNetworkMonitor();
        // Complete the onboarding state BEFORE the core starts, so the standalone
        // backend opens the main interface instead of the Welcome wizard.
        OnboardingSeeder.seed(ctx, rm.getAppDataDir(), rm.getProjectsDir());
        // If the env prefix survived a previous launch, its bridges are cheap to
        // (re)write now. A fresh env install rewrites them when it finishes.
        if (EnvInstaller.isInstalled(ctx)) {
            ShellBridgeInstaller.install(rm);
        }
    }

    /** True once the runtime was extracted and the completion marker written. */
    private static boolean isCoreExtracted(RuntimeManager rm) {
        return new File(rm.getRuntimeDir(), RuntimePaths.RUNTIME_MARKER).isFile()
                && new File(rm.getBinDir(), RuntimePaths.CORE_NAME).isFile();
    }

    /**
     * True when the runtime marker is at least as new as the installed APK: the
     * runtime was verified against the manifest during this APK's install and the
     * APK has not changed since, so the ~174MB re-hash can be skipped.
     */
    private static boolean runtimeMarkerFresh(RuntimeManager rm) {
        File marker = new File(rm.getRuntimeDir(), RuntimePaths.RUNTIME_MARKER);
        if (!marker.isFile()) {
            return false;
        }
        try {
            long lastUpdate = rm.appContext().getPackageManager()
                    .getPackageInfo(rm.appContext().getPackageName(), 0).lastUpdateTime;
            return marker.lastModified() >= lastUpdate;
        } catch (Exception e) {
            return false; // fail-closed: fall back to the full verify
        }
    }

    /** Touches the runtime marker so a warm launch skips the manifest re-hash. */
    private static void refreshRuntimeMarker(RuntimeManager rm) {
        File marker = new File(rm.getRuntimeDir(), RuntimePaths.RUNTIME_MARKER);
        if (!marker.setLastModified(System.currentTimeMillis())) {
            Log.w(TAG, "cannot refresh runtime marker: " + marker);
        }
    }

    private static boolean isWebExtracted(RuntimeManager rm) {
        File marker = new File(rm.getWebDir(), RuntimePaths.WEB_MARKER);
        if (!marker.isFile() || !new File(rm.getWebDir(), "main.js").isFile()) {
            return false;
        }
        try {
            long lastUpdate = rm.appContext().getPackageManager()
                    .getPackageInfo(rm.appContext().getPackageName(), 0).lastUpdateTime;
            if (marker.lastModified() < lastUpdate) {
                Log.i(TAG, "APK updated; refreshing assets/web");
                return false;
            }
        } catch (Exception ignored) {
        }
        return true;
    }

    private static void writeMarker(File dir, String name) {
        try {
            com.agy.util.AtomicFileWriter.writeText(new File(dir, name), "1\n");
        } catch (Exception e) {
            Log.w(TAG, "cannot write marker " + name + ": " + e.getMessage());
        }
    }

    private static void wipe(RuntimeManager rm, File dir) throws IOException {
        assertSafeToWipe(rm, dir);
        if (!dir.exists()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                wipe(rm, child);
            }
        }
        if (!dir.delete()) {
            throw new IOException("cannot delete " + dir);
        }
    }

    /**
     * Hard safety net: only regenerable state may be wiped. Refuses the app
     * files dir itself, the {@code $HOME/.gemini} user data, any ancestor of it,
     * and anything outside {@code getFilesDir()}.
     */
    private static void assertSafeToWipe(RuntimeManager rm, File dir) throws IOException {
        File files = rm.appContext().getFilesDir();
        String filesPath = files.getCanonicalPath();
        String geminiPath = new File(files, RuntimePaths.GEMINI_DIR).getCanonicalPath();
        String target = dir.getCanonicalPath();
        if (!target.startsWith(filesPath + File.separator)) {
            throw new IOException("refusing to wipe outside filesDir: " + dir);
        }
        if (target.equals(geminiPath)
                || target.startsWith(geminiPath + File.separator)
                || geminiPath.startsWith(target + File.separator)) {
            throw new IOException("refusing to wipe user data under .gemini: " + dir);
        }
    }
}
