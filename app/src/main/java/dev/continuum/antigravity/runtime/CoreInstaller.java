package dev.continuum.antigravity.runtime;

import android.content.Context;
import android.util.Log;

import dev.continuum.antigravity.install.EnvInstaller;
import dev.continuum.antigravity.util.AssetUnpacker;
import dev.continuum.antigravity.util.FileModeUtil;

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
        String expectedHash = getAssetManifestHash(ctx);
        boolean upToDate = isRuntimeUpToDate(rm, expectedHash);

        if (!upToDate) {
            Log.i(TAG, "extracting assets/runtime (first launch or runtime update)");
            try {
                wipe(rm, runtimeDir);
                AssetUnpacker.unpackTree(ctx.getAssets(), RuntimePaths.RUNTIME_ASSET, runtimeDir);
            } catch (IOException e) {
                throw new IllegalStateException(
                        "cannot unpack assets/runtime into " + runtimeDir
                                + ": " + e.getMessage(), e);
            }
            FileModeUtil.recursiveSetReadableExecutable(rm.getBinDir());
            FileModeUtil.recursiveSetReadableExecutable(rm.getLibDir());
            String manifestError = ManifestVerifier.verifyError(ctx, runtimeDir);
            if (manifestError != null) {
                throw new IllegalStateException(
                        "runtime integrity check failed: " + manifestError);
            }
            writeMarker(rm.getRuntimeDir(), RuntimePaths.RUNTIME_MARKER, expectedHash);
        } else {
            Log.i(TAG, "runtime up to date (manifest hash matched); skipping extract");
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
        OnboardingSeeder.seed(ctx, rm.getAppDataDir(), rm.getProjectsDir());
        // Always ensure fallback shells and open-url shims are installed
        // in runtime/bin immediately regardless of whether the bionic prefix is extracted.
        ShellBridgeInstaller.installFallbackBridges(rm.getBinDir(), ctx);
        ShellBridgeInstaller.installOpenUrlShims(rm.getBinDir(), ctx);
        // If the env prefix survived a previous launch, its bridges are cheap to
        // (re)write now. A fresh env install rewrites them when it finishes.
        if (EnvInstaller.isInstalled(ctx)) {
            ShellBridgeInstaller.install(rm);
        }
    }

    private static boolean isRuntimeUpToDate(RuntimeManager rm, String expectedHash) {
        if (expectedHash == null || expectedHash.isEmpty()) {
            return false;
        }
        File marker = new File(rm.getRuntimeDir(), RuntimePaths.RUNTIME_MARKER);
        File core = new File(rm.getBinDir(), RuntimePaths.CORE_NAME);
        File loader = new File(rm.getBinDir(), RuntimePaths.LOADER_NAME);
        File lseCompat = new File(rm.getLibDir(), "liblse_compat.so");
        if (!marker.isFile() || !core.isFile() || !loader.isFile() || !lseCompat.isFile()) {
            return false;
        }
        try {
            String actual = dev.continuum.antigravity.util.IoUtil.readAll(new java.io.FileInputStream(marker), "UTF-8").trim();
            return expectedHash.equals(actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static String getAssetManifestHash(Context ctx) {
        try (java.io.InputStream in = ctx.getAssets().open(RuntimePaths.MANIFEST_ASSET)) {
            return dev.continuum.antigravity.util.Sha256.of(in);
        } catch (Exception e) {
            Log.w(TAG, "cannot hash asset manifest: " + e.getMessage());
            return "";
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
        writeMarker(dir, name, "1");
    }

    private static void writeMarker(File dir, String name, String content) {
        try {
            dev.continuum.antigravity.util.AtomicFileWriter.writeText(new File(dir, name), content + "\n");
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
