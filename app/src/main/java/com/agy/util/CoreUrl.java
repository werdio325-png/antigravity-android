package com.agy.util;

/** Builds the core's loopback https URLs. */
public final class CoreUrl {

    private CoreUrl() {
    }

    public static String startUrl(int port) {
        return "https://127.0.0.1:" + port + "/";
    }

    public static String startUrl(int port, String csrfToken) {
        String url = startUrl(port);
        return csrfToken == null ? url : url + "?csrf_token=" + csrfToken;
    }

    public static String baseUrl(int port) {
        return "https://127.0.0.1:" + port;
    }
}
