package com.agy.install;

import android.content.res.AssetManager;
import android.util.Log;

import com.agy.util.FileModeUtil;

import java.io.File;
import java.io.IOException;

/** Installs the bundled bus scripts under libexec/bus. */
public final class BusInstaller {

    private static final String TAG = "BusInstaller";

    private BusInstaller() {
    }

    public static void install(AssetManager assets, File prefix) {
        String[] children;
        try {
            children = assets.list(EnvPaths.ASSET_BUS);
        } catch (IOException e) {
            Log.w(TAG, "cannot list assets/" + EnvPaths.ASSET_BUS + ": " + e);
            return;
        }
        if (children == null || children.length == 0) {
            Log.d(TAG, "no assets/" + EnvPaths.ASSET_BUS + " tree; skipping bus install");
            return;
        }
        File busDir = new File(prefix, "libexec/bus");
        OverlayCopier.copyTree(assets, EnvPaths.ASSET_BUS, busDir);
        File busSh = new File(busDir, "bus.sh");
        if (busSh.isFile()) {
            FileModeUtil.setExec(busSh);
        }
        markScriptsExecutable(busDir);
        Log.i(TAG, "bus installed: " + busDir);
    }

    private static void markScriptsExecutable(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isDirectory()) {
                markScriptsExecutable(f);
            } else {
                String name = f.getName();
                if (name.endsWith(".sh") || name.endsWith(".py")) {
                    FileModeUtil.setExec(f);
                }
            }
        }
    }
}
