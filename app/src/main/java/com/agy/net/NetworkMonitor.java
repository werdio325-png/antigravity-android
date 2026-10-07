package com.agy.net;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.util.Log;

/**
 * Watches the system default network and notifies its listener on every
 * connectivity change. Degrades to a no-op (older device, missing permission,
 * SecurityException) instead of breaking core startup.
 */
public final class NetworkMonitor {

    private static final String TAG = "NetworkMonitor";

    public interface Listener {
        void onNetworkChanged();
    }

    private final Context appContext;
    private final Listener listener;

    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback callback;
    private boolean registered;

    public NetworkMonitor(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    /** Idempotent. Safe to call from any thread; never throws. */
    public synchronized void register() {
        if (registered) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.i(TAG, "default network callback needs API 24; DNS refresh disabled");
            return;
        }
        try {
            ConnectivityManager cm =
                    (ConnectivityManager) appContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                Log.w(TAG, "ConnectivityManager unavailable; DNS refresh disabled");
                return;
            }
            ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    notifyChanged();
                }

                @Override
                public void onLost(Network network) {
                    notifyChanged();
                }

                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties linkProperties) {
                    notifyChanged();
                }
            };
            cm.registerDefaultNetworkCallback(cb);
            this.connectivityManager = cm;
            this.callback = cb;
            this.registered = true;
            Log.i(TAG, "registered default network callback");
        } catch (SecurityException e) {
            Log.w(TAG, "ACCESS_NETWORK_STATE missing; DNS refresh disabled", e);
        } catch (Exception e) {
            Log.w(TAG, "cannot register network callback; DNS refresh disabled", e);
        }
    }

    /** Idempotent teardown. Safe to call from any thread; never throws. */
    public synchronized void unregister() {
        if (!registered || connectivityManager == null || callback == null) {
            registered = false;
            callback = null;
            return;
        }
        try {
            connectivityManager.unregisterNetworkCallback(callback);
        } catch (Exception e) {
            Log.w(TAG, "cannot unregister network callback", e);
        } finally {
            registered = false;
            connectivityManager = null;
            callback = null;
        }
    }

    private void notifyChanged() {
        try {
            listener.onNetworkChanged();
        } catch (Exception e) {
            Log.w(TAG, "network change listener failed", e);
        }
    }
}
