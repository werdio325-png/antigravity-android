package com.agy.core;

import android.util.Log;

import com.agy.runtime.RuntimeManager;

import java.io.File;
import java.util.Map;

/** Builds the environment for the glibc core child process. */
public final class CoreEnvironment {

    private static final String TAG = "CoreEnvironment";

    private CoreEnvironment() {
    }

    public static void apply(RuntimeManager rm, ProcessBuilder pb) {
        File files = rm.appContext().getFilesDir();
        File bin = rm.getBinDir();
        Map<String, String> env = pb.environment();
        // HOME is the app files dir and never a temp dir; it is stable across
        // builds so the core always finds $HOME/.gemini (token, config, chats).
        env.put("HOME", files.getAbsolutePath());
        env.put("TMPDIR", rm.appContext().getCacheDir().getAbsolutePath());
        Log.i(TAG, "core env: HOME=" + files.getAbsolutePath()
                + " appDataDir=" + rm.getAppDataDir().getAbsolutePath()
                + " projectsDir=" + rm.getProjectsDir().getAbsolutePath()
                + " TMPDIR=" + rm.appContext().getCacheDir().getAbsolutePath());
        env.put("SSL_CERT_FILE", rm.getCertFile().getAbsolutePath());
        String inheritedPath = env.get("PATH");
        env.put("PATH", bin.getAbsolutePath()
                + (inheritedPath == null || inheritedPath.isEmpty() ? "" : ":" + inheritedPath));
        // The core's PTY spawner uses $SHELL (or falls back to /bin/bash, which
        // does not exist on Android). Point it at the bridge in runtime/bin.
        env.put("SHELL", new File(bin, "bash").getAbsolutePath());
        env.put("LANG", "C.UTF-8");
        env.put("LC_ALL", "C.UTF-8");
        // Pure-Go resolver keeps DNS independent of Android's missing /etc.
        String godebug = env.get("GODEBUG");
        env.put("GODEBUG", godebug == null || godebug.isEmpty()
                ? "netdns=go" : godebug + ",netdns=go");
        // The glibc loader resolves libraries via --library-path; keep bionic's out of the way.
        env.remove("LD_LIBRARY_PATH");
    }
}
