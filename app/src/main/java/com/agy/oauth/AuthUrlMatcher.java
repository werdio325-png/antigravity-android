package com.agy.oauth;

import android.net.Uri;

import java.util.Locale;

/** Recognizes the URLs that must leave the WebView for the system browser. */
public final class AuthUrlMatcher {

    public boolean isAuthUrl(String url) {
        if (url == null) {
            return false;
        }
        Uri uri;
        try {
            uri = Uri.parse(url);
        } catch (Exception e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) {
            return false;
        }
        host = host.toLowerCase(Locale.US);
        if (host.equals("accounts.google.com") || host.endsWith(".accounts.google.com")
                || host.equals("accounts.googleusercontent.com")
                || host.equals("oauth2.googleapis.com")) {
            return true;
        }
        String path = uri.getPath();
        if (path == null) {
            path = "";
        }
        // The OAuth authorize endpoint: google.com/o/oauth2[/v2]/auth.
        if ((host.equals("google.com") || host.endsWith(".google.com"))
                && path.contains("/o/oauth2")) {
            return true;
        }
        // The app's own auth entry point.
        if ((host.equals("antigravity.google") || host.endsWith(".antigravity.google"))
                && path.contains("/auth")) {
            return true;
        }
        return false;
    }
}
