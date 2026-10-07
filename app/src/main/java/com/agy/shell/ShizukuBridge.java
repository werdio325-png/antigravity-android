package com.agy.shell;

import android.content.Context;

/**
 * Rootless shell access through the bundled Shizuku `rish` bridge.
 * No Shizuku API library is linked; `rish` is executed as a child process.
 */
public final class ShizukuBridge {

    private final RishLocator locator;
    private final RishExecutor executor;

    public ShizukuBridge(Context context) {
        this.locator = new RishLocator(context);
        this.executor = new RishExecutor(context);
    }

    public boolean isAvailable() {
        java.io.File rish = locator.rishBinary();
        return rish.exists() && rish.canExecute();
    }

    /** Runs `cmd` as shell via rish. Returns combined stdout/stderr; errors are text. */
    public String exec(String cmd) {
        return executor.exec(cmd);
    }
}
