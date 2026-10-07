package com.agy.runtime;

import android.content.Context;

import com.agy.net.NetworkMonitor;

/** Owns the single network monitor for the process. */
public final class NetworkMonitorHolder {

    private final Context appContext;
    private NetworkMonitor monitor;

    public NetworkMonitorHolder(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** Registers the default-network callback once; refreshes DNS on changes. */
    public synchronized void start() {
        if (monitor == null) {
            monitor = new NetworkMonitor(appContext, new NetworkMonitor.Listener() {
                @Override
                public void onNetworkChanged() {
                    NetworkConfigWriter.write(appContext);
                }
            });
        }
        monitor.register();
    }

    /** Safe to call on teardown, repeatedly. */
    public synchronized void unregister() {
        if (monitor != null) {
            monitor.unregister();
        }
    }
}
