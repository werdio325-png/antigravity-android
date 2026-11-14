package dev.continuum.antigravity.util;

import android.net.Uri;

/** Exact origin for the embedded core UI; a loopback hostname alone is not trusted. */
public final class TrustedCoreOrigin {

    private TrustedCoreOrigin() {
    }

    public static boolean matches(Uri uri, int expectedPort) {
        return uri != null
                && "https".equalsIgnoreCase(uri.getScheme())
                && "127.0.0.1".equals(uri.getHost())
                && uri.getUserInfo() == null
                && uri.getPort() == expectedPort;
    }
}
