package dev.continuum.antigravity.install;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import dev.continuum.antigravity.util.AssetPicker;
import dev.continuum.antigravity.util.AtomicFileWriter;
import dev.continuum.antigravity.util.FileModeUtil;
import dev.continuum.antigravity.util.PathSafety;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Installs and migrates the mutable Bionic prefix without deleting package/user state. */
public final class EnvInstaller {

    private static final String TAG = "EnvInstaller";
    private static final String STATE_SCHEMA = "2";

    private EnvInstaller() {
    }

    public static File prefix(Context context) {
        return EnvPaths.prefix(context);
    }

    public static boolean isInstalled(Context context) {
        File prefix = prefix(context);
        return new File(prefix, EnvPaths.MARKER).isFile()
                && new File(prefix, EnvPaths.BASH_REL).isFile();
    }

    /** @return true when the prefix was built in this call. */
    public static synchronized boolean install(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("null context");
        }
        Context ctx = context.getApplicationContext();
        if (ctx == null) {
            ctx = context;
        }
        AssetManager assets = ctx.getAssets();
        File prefix = prefix(ctx);
        validatePrefixLocation(ctx, prefix);

        EnvBundleManifest manifest = EnvBundleManifest.load(assets);
        String bootstrapAsset = AssetPicker.pick(assets, EnvPaths.ASSET_BOOTSTRAP, EnvPaths.ASSET_BOOTSTRAP_PLAIN);
        if (bootstrapAsset == null) {
            throw new IllegalStateException("required asset missing: " + EnvPaths.ASSET_BOOTSTRAP);
        }
        if (isInstalled(ctx)) {
            updateManagedLayers(ctx, assets, prefix, bootstrapAsset, manifest);
            return false;
        }
        installFresh(ctx, assets, prefix, bootstrapAsset, manifest);
        return true;
    }

    private static void validatePrefixLocation(Context context, File prefix) {
        try {
            File filesDir = context.getFilesDir();
            File userData = new File(filesDir, EnvPaths.USER_DATA_DIR);
            if (!PathSafety.isInside(filesDir, prefix)
                    || PathSafety.isInside(userData, prefix)
                    || PathSafety.isInside(prefix, userData)) {
                throw new IllegalStateException(
                        "refusing unsafe env prefix location: " + prefix);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot resolve prefix " + prefix, e);
        }
    }

    private static void installFresh(Context context, AssetManager assets, File prefix,
                                     String bootstrapAsset, EnvBundleManifest manifest) {
        File parent = prefix.getParentFile();
        if (parent == null || (!parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory())) {
            throw new IllegalStateException("cannot create prefix parent: " + parent);
        }
        File stage = new File(parent, ".usr-stage-" + UUID.randomUUID());
        File quarantined = null;
        try {
            if (!stage.mkdirs() && !stage.isDirectory()) {
                throw new IOException("cannot create staging prefix: " + stage);
            }
            String targetPrefix = prefix.getAbsolutePath();
            BootstrapExtractor.extract(assets, bootstrapAsset, stage,
                    manifest.bootstrapSha256, manifest.bootstrapSymlinkCount);
            OverlayCopier.overlay(assets, EnvPaths.ASSET_OVERLAY, stage);
            PkgInstaller.install(assets, stage, targetPrefix);
            prepareTree(assets, stage, targetPrefix);
            writeMarker(stage, manifest, manifest.bootstrapSha256,
                    manifest.bootstrapSha256, false, targetPrefix);
            validateTree(stage, manifest.bootstrapSymlinkCount);

            // Preserve a previous interrupted/legacy prefix instead of deleting it.
            if (prefix.exists() && !TextFileDetector.isSymlink(prefix)) {
                quarantined = new File(parent, ".usr-incomplete-" + UUID.randomUUID());
                if (!prefix.renameTo(quarantined)) {
                    throw new IOException("cannot preserve incomplete prefix " + prefix);
                }
            } else if (TextFileDetector.isSymlink(prefix)) {
                quarantined = new File(parent, ".usr-incomplete-" + UUID.randomUUID());
                if (!prefix.renameTo(quarantined)) {
                    throw new IOException("cannot preserve prefix link " + prefix);
                }
            }
            if (!stage.renameTo(prefix)) {
                restoreQuarantine(prefix, quarantined);
                throw new IOException("cannot atomically publish staging prefix " + stage);
            }
            if (!bashSmokeTest(context, prefix)) {
                File failed = new File(parent, ".usr-failed-" + UUID.randomUUID());
                if (!prefix.renameTo(failed)) {
                    Log.e(TAG, "new prefix failed smoke test and could not be quarantined: " + prefix);
                }
                restoreQuarantine(prefix, quarantined);
                throw new IOException("installed bash failed its smoke test; prefix preserved for diagnosis");
            }
            Log.i(TAG, "env prefix installed atomically: " + prefix
                    + " bundle=" + manifest.bundleId);
        } catch (Exception e) {
            deleteTreeQuietly(stage, context.getFilesDir());
            throw new IllegalStateException("env install failed: " + e.getMessage(), e);
        }
    }

    /** Refreshes AGY-owned layers but never wipes the mutable prefix or .gemini data. */
    private static void updateManagedLayers(Context context, AssetManager assets, File prefix,
                                            String bootstrapAsset, EnvBundleManifest manifest) {
        Properties previous = readState(new File(prefix, EnvPaths.MARKER));
        String oldBootstrap = previous.getProperty("bootstrap_sha256");
        String oldOverlay = previous.getProperty("overlay_sha256");
        String oldPkg = previous.getProperty("pkg_sha256");
        String oldPath = previous.getProperty("prefix_path");
        String targetPrefix = prefix.getAbsolutePath();

        // A full bootstrap replacement can overwrite package-managed/user edits.
        // Preserve legacy/mutable prefixes and report the pending base migration
        // instead of silently deleting or rebasing their contents.
        boolean bootstrapKnown = oldBootstrap != null && !"legacy".equals(oldBootstrap);
        boolean bootstrapMatches = manifest.bootstrapSha256.equals(oldBootstrap);
        boolean bootstrapPending = !bootstrapMatches;
        String appliedBootstrap = bootstrapKnown ? oldBootstrap : "legacy";
        if (bootstrapKnown && bootstrapPending) {
            Log.w(TAG, "bootstrap changed; preserving mutable prefix until an explicit migration "
                    + "is available (installed=" + oldBootstrap + ", bundled="
                    + manifest.bootstrapSha256 + ")");
        } else if (!bootstrapKnown && bootstrapPending) {
            Log.w(TAG, "legacy prefix has no base version; preserving it and refreshing only "
                    + "AGY-managed overlay/pkg layers");
        }

        boolean overlayChanged = !manifest.overlaySha256.equals(oldOverlay);
        boolean pkgChanged = !manifest.pkgSha256.equals(oldPkg);
        if (overlayChanged) {
            OverlayCopier.overlay(assets, EnvPaths.ASSET_OVERLAY, prefix);
        }
        if (pkgChanged) {
            PkgInstaller.install(assets, prefix, targetPrefix);
        }

        boolean prefixMoved = !targetPrefix.equals(oldPath);
        if (prefixMoved || overlayChanged || pkgChanged) {
            TermuxSymlinkFixer.fix(prefix, targetPrefix);
            TermuxShebangRewriter.rewrite(prefix, targetPrefix);
            TermuxPrefixRewriter.rewrite(prefix, targetPrefix);
        }
        if (overlayChanged || pkgChanged) {
            FileModeUtil.recursiveSetExec(new File(prefix, "bin"));
        }
        FileModeUtil.setExec(new File(prefix, "libexec/pkg/pkg"));
        File fixPaths = new File(prefix, EnvPaths.FIX_PATHS_REL);
        if (fixPaths.isFile()) {
            FileModeUtil.setExec(fixPaths);
        }
        BusInstaller.install(assets, prefix);
        TermuxHooksRemover.remove(prefix);
        validateTree(prefix, 0);
        writeMarker(prefix, manifest, appliedBootstrap,
                manifest.bootstrapSha256, bootstrapPending, targetPrefix);
        Log.i(TAG, "env managed layers ready: overlay=" + (overlayChanged ? "updated" : "current")
                + " pkg=" + (pkgChanged ? "updated" : "current")
                + " bootstrap=" + (bootstrapPending ? "pending" : "current"));
    }

    private static void prepareTree(AssetManager assets, File root, String targetPrefix) {
        // Repair every path representation before anything can execute from the prefix.
        TermuxSymlinkFixer.fix(root, targetPrefix);
        TermuxShebangRewriter.rewrite(root, targetPrefix);
        TermuxPrefixRewriter.rewrite(root, targetPrefix);
        FileModeUtil.recursiveSetExec(new File(root, "bin"));
        FileModeUtil.setExec(new File(root, "libexec/pkg/pkg"));
        File fixPaths = new File(root, EnvPaths.FIX_PATHS_REL);
        if (fixPaths.isFile()) {
            FileModeUtil.setExec(fixPaths);
        }
        BusInstaller.install(assets, root);
        TermuxHooksRemover.remove(root);
    }

    private static void validateTree(File prefix, int minimumSymlinks) {
        File bin = new File(prefix, "bin");
        requireFile(new File(bin, "bash"), "bash");
        requireFile(new File(bin, "sh"), "sh");
        requireFile(new File(bin, "pkg"), "pkg wrapper");
        requireFile(new File(prefix, "lib/libtermux-exec.so"), "libtermux-exec.so");
        if (minimumSymlinks > 0) {
            int actual = countSymlinks(prefix);
            if (actual < minimumSymlinks) {
                throw new IllegalStateException("bootstrap symlink set incomplete: " + actual
                        + " < " + minimumSymlinks);
            }
        }
    }

    private static void requireFile(File file, String name) {
        if (!file.isFile()) {
            throw new IllegalStateException("required env file missing: " + name + " at " + file);
        }
    }

    private static int countSymlinks(File dir) {
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        int count = 0;
        for (File child : children) {
            if (TextFileDetector.isSymlink(child)) {
                count++;
            } else if (child.isDirectory()) {
                count += countSymlinks(child);
            }
        }
        return count;
    }

    private static Properties readState(File marker) {
        Properties state = new Properties();
        if (!marker.isFile()) {
            return state;
        }
        try (FileInputStream in = new FileInputStream(marker)) {
            state.load(in);
        } catch (Exception e) {
            Log.w(TAG, "cannot read env state " + marker + ": " + e.getMessage());
        }
        return state;
    }

    private static void writeMarker(File prefix, EnvBundleManifest manifest,
                                    String appliedBootstrap, String targetBootstrap,
                                    boolean pending, String targetPrefix) {
        String content = "schema=" + STATE_SCHEMA + "\n"
                + "bundle_id=" + manifest.bundleId + "\n"
                + "bootstrap_sha256=" + appliedBootstrap + "\n"
                + "bootstrap_target_sha256=" + targetBootstrap + "\n"
                + "bootstrap_update_pending=" + pending + "\n"
                + "overlay_sha256=" + manifest.overlaySha256 + "\n"
                + "pkg_sha256=" + manifest.pkgSha256 + "\n"
                + "prefix_path=" + targetPrefix + "\n";
        File marker = new File(prefix, EnvPaths.MARKER);
        AtomicFileWriter.writeText(marker, content);
        Properties written = readState(marker);
        if (!manifest.bundleId.equals(written.getProperty("bundle_id"))) {
            throw new IllegalStateException("cannot persist env state marker: " + marker);
        }
    }

    private static boolean bashSmokeTest(Context context, File prefix) {
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(
                    new File(prefix, "bin/bash").getAbsolutePath(), "-c", "exit 0");
            builder.directory(context.getFilesDir());
            builder.redirectErrorStream(true);
            builder.environment().put("PREFIX", prefix.getAbsolutePath());
            builder.environment().put("HOME", new File(prefix.getParentFile(), "home").getAbsolutePath());
            builder.environment().put("TMPDIR", new File(prefix, "tmp").getAbsolutePath());
            builder.environment().put("PATH", new File(prefix, "bin").getAbsolutePath() + ":/system/bin");
            builder.environment().put("LD_LIBRARY_PATH", new File(prefix, "lib").getAbsolutePath());
            File preload = new File(prefix, "lib/libtermux-exec.so");
            if (preload.isFile()) {
                builder.environment().put("LD_PRELOAD", preload.getAbsolutePath());
            }
            builder.environment().put("AGY_APPLICATION_ID", context.getPackageName());
            process = builder.start();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            Log.w(TAG, "bash smoke test failed: " + e.getMessage());
            return false;
        }
    }

    private static void restoreQuarantine(File prefix, File quarantined) {
        if (quarantined != null && quarantined.exists() && !prefix.exists()
                && !quarantined.renameTo(prefix)) {
            Log.e(TAG, "cannot restore preserved prefix " + quarantined + " -> " + prefix);
        }
    }

    private static void deleteTreeQuietly(File root, File filesDir) {
        if (root == null || !root.exists() || TextFileDetector.isSymlink(root)) {
            if (root != null && TextFileDetector.isSymlink(root)) {
                root.delete();
            }
            return;
        }
        try {
            if (!PathSafety.isInside(filesDir, root) || root.equals(filesDir)) {
                Log.e(TAG, "refusing to clean unsafe staging path: " + root);
                return;
            }
            File[] children = root.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteTreeQuietly(child, filesDir);
                }
            }
            if (!root.delete()) {
                Log.w(TAG, "cannot remove failed staging path: " + root);
            }
        } catch (IOException e) {
            Log.w(TAG, "cannot validate failed staging path: " + e.getMessage());
        }
    }
}
