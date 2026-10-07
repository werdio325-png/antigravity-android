package com.agy.core;

import com.agy.util.ProcessUtils;

import java.net.InetSocketAddress;
import java.net.Socket;

/** One-shot TCP probe of the core's loopback port. */
public final class CoreReadyProbe {

    public static final long READY_TIMEOUT_MS = 30000;
    /** Re-probe gap while the core boots; the port usually answers within ~1s. */
    private static final long RETRY_SLEEP_MS = 60;

    private CoreReadyProbe() {
    }

    public static boolean probeOnce(int port) {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
            return true;
        } catch (Exception notReady) {
            ProcessUtils.sleep(RETRY_SLEEP_MS);
            return false;
        } finally {
            if (socket != null) {
                try {
                    socket.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
