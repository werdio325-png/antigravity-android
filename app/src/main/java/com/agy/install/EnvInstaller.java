package com.agy.install;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.agy.util.AssetPicker;
import com.agy.util.FileModeUtil;
import com.agy.util.PathSafety;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Installs the bundled bionic toolchain (assets/env) into the app-private
 * prefix {@code getFilesDir()/usr}. Fail-closed, idempotent (marker-guarded).
 */
public final class EnvInstaller {

    private static final String TAG = "EnvInstaller";

    private EnvInstaller() {
    }

    public static File prefix(Context context) {
        return EnvPaths.prefix(context);
    }

    public static boolean isInstalled(Context context) {
        File prefix = prefix(context);
        return new File(prefix, EnvPaths.MARKER).isFile()
                && new File(prefix, EnvPaths.BASH_REL).exists();
    }

    /** @return true if the env was extracted this call, false if it was already installed. */
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

        // Safety net: the regenerable prefix is $HOME/usr, never $HOME/.gemini.
        try {
            File userData = new File(ctx.getFilesDir(), EnvPaths.USER_DATA_DIR);
            if (PathSafety.isInside(userData, prefix)) {
                throw new IllegalStateException(
                        "refusing to install env over user data: " + prefix);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot resolve prefix " + prefix, e);
        }

        // Order matters: an already-installed prefix wins over the asset check so
        // a missing/renamed bootstrap asset cannot block boot on an installed env.
        if (isInstalled(ctx)) {
            Log.i(TAG, "env already installed at " + prefix);
            BusInstaller.install(assets, prefix);
            return false;
        }

        // Fail-closed only when neither the prefix nor a usable asset exists.
        String bootstrapAsset = AssetPicker.pick(assets,
                EnvPaths.ASSET_BOOTSTRAP, EnvPaths.ASSET_BOOTSTRAP_PLAIN);
        if (bootstrapAsset == null) {
            throw new IllegalStateException(
                    "required asset missing: " + EnvPaths.ASSET_BOOTSTRAP
                            + " (or " + EnvPaths.ASSET_BOOTSTRAP_PLAIN + ")");
        }
        Log.i(TAG, "installing env toolchain into " + prefix);

        if (!prefix.isDirectory() && !prefix.mkdirs() && !prefix.isDirectory()) {
            throw new IllegalStateException("cannot create prefix: " + prefix);
        }

        BootstrapExtractor.extract(assets, bootstrapAsset, prefix);
        if (!new File(prefix, "bin").isDirectory()) {
            throw new IllegalStateException("bootstrap produced no bin/ under " + prefix);
        }

        OverlayCopier.overlay(assets, EnvPaths.ASSET_OVERLAY, prefix);
        PkgInstaller.install(assets, prefix);
        // Repoint termux shebangs/symlinks before anything (shell bridges, user
        // tools) can exec from the prefix. Runs once per extraction (install is
        // marker-guarded); idempotent if run again.
        TermuxSymlinkFixer.fix(prefix);
        TermuxShebangRewriter.rewrite(prefix);
        TermuxPrefixRewriter.rewrite(prefix);
        FileModeUtil.recursiveSetExec(new File(prefix, "bin"));
        FileModeUtil.setExec(new File(prefix, "libexec/pkg/pkg"));
        // Ship the reusable post-install fixer: users run it after pip/npm so
        // newly written console scripts are repointed at our prefix too.
        File fixPaths = new File(prefix, EnvPaths.FIX_PATHS_REL);
        if (fixPaths.isFile()) {
            FileModeUtil.setExec(fixPaths);
            Log.i(TAG, "path fixer available: " + fixPaths);
        } else {
            Log.w(TAG, "path fixer missing (asset overlay/bin/agy-fix-paths): " + fixPaths);
        }
        BusInstaller.install(assets, prefix);
        TermuxHooksRemover.remove(prefix);
        writeMarker(prefix);

        Log.i(TAG, "env install complete: " + prefix);
        return true;
    }

    private static void writeMarker(File prefix) {
        File marker = new File(prefix, EnvPaths.MARKER);
        try (OutputStream out = new FileOutputStream(marker)) {
            out.write("1\n".getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("cannot write marker " + marker + ": " + e.getMessage(), e);
        }
    }
}
