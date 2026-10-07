package com.agy.util;

import java.io.IOException;
import java.io.InputStream;

/** Discards a process stream so the child never blocks on a full pipe. */
public final class StreamDrain {

    private StreamDrain() {
    }

    public static void drain(InputStream in) throws IOException {
        byte[] buffer = new byte[8192];
        while (in.read(buffer) > 0) {
            // discard process output
        }
    }
}
