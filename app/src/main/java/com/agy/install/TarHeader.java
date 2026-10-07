package com.agy.install;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Low-level ustar header field parsing. */
public final class TarHeader {

    private TarHeader() {
    }

    public static boolean readBlock(InputStream in, byte[] block) throws IOException {
        int off = 0;
        while (off < block.length) {
            int read = in.read(block, off, block.length - off);
            if (read < 0) {
                if (off == 0) {
                    return false;
                }
                throw new IOException("truncated tar header");
            }
            off += read;
        }
        return true;
    }

    public static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    public static String readField(byte[] block, int off, int len) {
        int end = off + len;
        int i = off;
        while (i < end && block[i] != 0) {
            i++;
        }
        return new String(block, off, i - off, StandardCharsets.UTF_8);
    }

    public static long readOctal(byte[] block, int off, int len) {
        int end = off + len;
        int i = off;
        while (i < end && (block[i] == ' ' || block[i] == 0)) {
            i++;
        }
        int start = i;
        while (i < end && block[i] >= '0' && block[i] <= '7') {
            i++;
        }
        if (i == start) {
            return 0;
        }
        return Long.parseLong(new String(block, start, i - start, StandardCharsets.US_ASCII), 8);
    }
}
