package com.agy.util;

import android.net.Uri;

/** Loopback host detection for the self-signed core. */
public final class LoopbackHost {

    private LoopbackHost() {
    }

    public static boolean isLoopback(String host) {
        return "127.0.0.1".equals(host) || "localhost".equals(host);
    }

    public static boolean isLoopback(Uri uri) {
        return uri != null && isLoopback(uri.getHost());
    }
}
