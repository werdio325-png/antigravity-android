package dev.continuum.antigravity.runtime;

import android.content.Context;
import android.util.Log;

import dev.continuum.antigravity.net.DnsCollector;
import dev.continuum.antigravity.util.AtomicFileWriter;

import java.io.File;

/**
 * The core reads "etc//resolv.conf" (cwd = getFilesDir()). Android has no
 * /etc/resolv.conf, so materialize one plus a minimal hosts file. Both are
 * written atomically so the core never sees a half-written resolv.conf.
 */
public final class NetworkConfigWriter {

    private static final String TAG = "NetworkConfigWriter";

    private NetworkConfigWriter() {
    }

    public static void write(Context appContext) {
        File etc = new File(appContext.getFilesDir(), "etc");
        if (!etc.exists() && !etc.mkdirs()) {
            Log.w(TAG, "cannot create " + etc);
            return;
        }
        StringBuilder resolv = new StringBuilder();
        resolv.append("options timeout:1 attempts:1 rotate\n");
        for (String ip : DnsCollector.collectDnsServers(appContext)) {
            resolv.append("nameserver ").append(ip).append('\n');
        }
        AtomicFileWriter.writeText(new File(etc, "resolv.conf"), resolv.toString());
        AtomicFileWriter.writeText(new File(etc, "hosts"), "127.0.0.1 localhost\n");
    }
}
