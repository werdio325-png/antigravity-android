package com.agy.core;

/** Restart budget and exponential backoff for the core supervisor. */
public final class CoreRestartPolicy {

    /** Uptime that proves the core is stable and refills the restart budget. */
    public static final long SUSTAINED_READY_MS = 60000;
    public static final int MAX_RESTARTS = 3;
    public static final long RESTART_BASE_MS = 1000;
    public static final long RESTART_MAX_MS = 30000;

    private CoreRestartPolicy() {
    }

    public static long delayFor(int attempt) {
        int shift = Math.min(attempt - 1, 5);
        return Math.min(RESTART_BASE_MS << shift, RESTART_MAX_MS);
    }
}
