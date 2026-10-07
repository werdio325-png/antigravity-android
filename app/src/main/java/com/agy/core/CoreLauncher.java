package com.agy.core;

import com.agy.runtime.RuntimeManager;

import java.io.File;
import java.io.IOException;

/** Installs (phase 1 + phase 2) then launches the patched core process. */
public final class CoreLauncher {

    private CoreLauncher() {
    }

    public static Process launch(RuntimeManager runtime, File filesDir) throws IOException {
        // Phase 1 only: extract + verify the core (fail-closed, throws on
        // corruption) and return. Never wait on the ~90 MB bionic env here.
        runtime.installCore();
        // Phase 2: env toolchain + bridges on a daemon thread, in parallel
        // with core startup. Best-effort; the core boots without it.
        runtime.installEnvInBackground();
        ProcessBuilder pb = new ProcessBuilder(runtime.coreCommand());
        pb.directory(filesDir);
        pb.redirectErrorStream(true);
        runtime.applyEnvironment(pb);
        return pb.start();
    }
}
