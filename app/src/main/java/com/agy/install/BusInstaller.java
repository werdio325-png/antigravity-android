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
            Log.w(TAG, "no assets/" + EnvPaths.ASSET_BUS + " tree; skipping bus install");
            return;
        }
        File busDir = new File(prefix, "libexec/bus");
        OverlayCopier.copyTree(assets, EnvPaths.ASSET_BUS, busDir);
        File busSh = new File(busDir, "bus.sh");
        if (busSh.isFile()) {
            FileModeUtil.setExec(busSh);
        } else {
            Log.w(TAG, "bus.sh missing after install: " + busSh);
        }
        Log.i(TAG, "bus installed: " + busDir);
    }
}
