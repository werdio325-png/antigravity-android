package com.agy.bridge;

import com.agy.shell.ShizukuBridge;

/** Shizuku shell access exposed to the Web UI. */
public final class ShellBridge {

    private final ShizukuBridge shizuku;

    public ShellBridge(ShizukuBridge shizuku) {
        this.shizuku = shizuku;
    }

    public String exec(String cmd) {
        return shizuku.exec(cmd);
    }

    public boolean available() {
        return shizuku.isAvailable();
    }
}
