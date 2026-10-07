package com.agy.util;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/** Process helpers: interrupts, liveness and bounded wait/drain. */
public final class ProcessUtils {

    private ProcessUtils() {
    }

    public static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static boolean isAlive(Process proc) {
        try {
            proc.exitValue();
            return false;
        } catch (IllegalThreadStateException stillRunning) {
            return true;
        }
    }

    public static int exitCode(Process proc) {
        try {
            return proc.exitValue();
        } catch (IllegalThreadStateException stillRunning) {
            return -1;
        }
    }

    /** Drains stdout then waits up to {@code timeout}; true when it exited in time. */
    public static boolean drainAndWait(Process p, long timeout, TimeUnit unit) {
        try (InputStream in = p.getInputStream()) {
            StreamDrain.drain(in);
        } catch (Exception ignored) {
        }
        try {
            return p.waitFor(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
