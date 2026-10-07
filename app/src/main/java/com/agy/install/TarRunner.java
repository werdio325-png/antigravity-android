package com.agy.install;

import android.util.Log;

import com.agy.util.ProcessUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Extracts the bootstrap with toybox tar, then the plain system tar. */
public final class TarRunner {

    private static final String TAG = "TarRunner";

    private TarRunner() {
    }

    public static boolean runToyboxTar(File archive, File prefix, boolean gzip) {
        if (runTar(EnvPaths.TOYBOX, true, archive, prefix, gzip)) {
            return true;
        }
        return runTar(EnvPaths.TAR, false, archive, prefix, gzip);
    }

    private static boolean runTar(String exe, boolean viaToybox, File archive, File prefix,
                                  boolean gzip) {
        if (!new File(exe).isFile()) {
            return false;
        }
        List<String> cmd = new ArrayList<>();
        cmd.add(exe);
        if (viaToybox) {
            cmd.add("tar");
        }
        cmd.add(gzip ? "-xzf" : "-xf");
        cmd.add(archive.getAbsolutePath());
        cmd.add("-C");
        cmd.add(prefix.getAbsolutePath());
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            if (!ProcessUtils.drainAndWait(p, 120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                Log.w(TAG, "tar timed out: " + cmd);
                return false;
            }
            int rc = p.exitValue();
            if (rc != 0) {
                Log.w(TAG, "tar exit " + rc + ": " + cmd);
            }
            return rc == 0;
        } catch (Exception e) {
            Log.w(TAG, "tar exec failed " + cmd + ": " + e);
            return false;
        }
    }
}
