package com.antigravity.mobile.dev;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.Build;
import android.util.Log;

import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;

public final class VpnBypassManager {

    private static final String TAG = "VpnBypassManager";
    private static volatile boolean initialized = false;

    private VpnBypassManager() {}

    public static synchronized void applyBypass(Context context) {
        if (initialized) return;
        initialized = true;

        // 1. Configure Java-level non-proxy host system properties
        try {
            System.setProperty("http.nonProxyHosts", "localhost|127.*|[::1]|0.0.0.0");
            System.setProperty("https.nonProxyHosts", "localhost|127.*|[::1]|0.0.0.0");
        } catch (Exception ignored) {}

        // 2. Install custom ProxySelector to enforce direct loopback connection for Java HttpURLConnection
        try {
            final ProxySelector defaultSelector = ProxySelector.getDefault();
            ProxySelector.setDefault(new ProxySelector() {
                @Override
                public List<Proxy> select(URI uri) {
                    if (uri != null) {
                        String host = uri.getHost();
                        if ("127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host)) {
                            return Collections.singletonList(Proxy.NO_PROXY);
                        }
                    }
                    return defaultSelector != null ? defaultSelector.select(uri) : Collections.singletonList(Proxy.NO_PROXY);
                }

                @Override
                public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                    if (defaultSelector != null) {
                        defaultSelector.connectFailed(uri, sa, ioe);
                    }
                }
            });
            PerfLogger.log("VpnBypassManager: Custom ProxySelector installed");
        } catch (Exception e) {
            Log.e(TAG, "Failed to set custom ProxySelector", e);
        }

        // 3. Clear process-level network binding so loopback is not trapped in VPN tunnel
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && context != null) {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    cm.bindProcessToNetwork(null);
                    PerfLogger.log("VpnBypassManager: bindProcessToNetwork(null) applied");
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to unbind process network", e);
        }

        // 4. Configure Chromium WebView ProxyController override to bypass loopback
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                ProxyConfig proxyConfig = new ProxyConfig.Builder()
                    .addBypassRule("127.0.0.1")
                    .addBypassRule("localhost")
                    .addBypassRule("<-loopback>")
                    .addBypassRule("127.0.0.1:" + CoreServerService.PORT)
                    .addDirect()
                    .build();

                ProxyController.getInstance().setProxyOverride(proxyConfig, Runnable::run, () -> {
                    PerfLogger.log("VpnBypassManager: Chromium ProxyController override active (loopback bypass)");
                });
            } else {
                PerfLogger.log("VpnBypassManager: PROXY_OVERRIDE feature not supported by current WebView provider");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to configure Chromium ProxyController", e);
        }
    }
}
