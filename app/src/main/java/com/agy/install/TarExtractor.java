package com.agy.install;

import android.system.ErrnoException;
import android.system.Os;

import com.agy.util.FileModeUtil;
import com.agy.util.PathSafety;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.zip.GZIPInputStream;

/** Pure-Java tar fallback with path-checked regular files, directories and symlinks. */
public final class TarExtractor {

    private static final String TAG = "TarExtractor";

    private TarExtractor() {
    }

    public static int untarGz(InputStream raw, File dest) throws IOException {
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(raw, 1 << 16), 1 << 16)) {
            return untar(in, dest);
        }
    }

    /** @return number of symlinks materialized from the archive. */
    public static int untar(InputStream in, File dest) throws IOException {
        byte[] header = new byte[512];
        String pendingName = null;
        String pendingLink = null;
        int links = 0;
        while (TarHeader.readBlock(in, header)) {
            if (TarHeader.isZeroBlock(header)) {
                break;
            }
            String name = TarHeader.readField(header, 0, 100);
            long mode = TarHeader.readOctal(header, 100, 8);
            long size = TarHeader.readOctal(header, 124, 12);
            int type = header[156] & 0xff;
            String link = TarHeader.readField(header, 157, 100);
            String dirPrefix = TarHeader.readField(header, 345, 155);
            if (type == 0) {
                type = '0';
            }
            if (type == 'L') {
                pendingName = new String(readEntry(in, size), StandardCharsets.UTF_8).trim();
                continue;
            }
            if (type == 'K') {
                pendingLink = new String(readEntry(in, size), StandardCharsets.UTF_8).trim();
                continue;
            }
            if (type == 'x' || type == 'g') {
                skipEntry(in, size);
                continue;
            }
            if (pendingName != null) {
                name = pendingName;
                pendingName = null;
            }
            if (pendingLink != null) {
                link = pendingLink;
                pendingLink = null;
            }
            if (dirPrefix != null && !dirPrefix.isEmpty()) {
                name = dirPrefix + "/" + name;
            }
            name = TarPath.normalize(name);
            if (name == null) {
                throw new IOException("unsafe tar entry path");
            }
            if (name.isEmpty()) {
                skipEntry(in, size);
                continue;
            }
            File out = new File(dest, name);
            ensureParentInside(dest, out);

            if (type == '5') {
                skipEntry(in, size);
                removeConflictingLeaf(out, true);
                if (!out.isDirectory() && !out.mkdirs() && !out.isDirectory()) {
                    throw new IOException("cannot create dir " + out);
                }
                FileModeUtil.applyMode(out, mode);
            } else if (type == '0') {
                removeConflictingLeaf(out, false);
                writeFile(in, out, size, dest);
                FileModeUtil.applyMode(out, mode);
            } else if (type == '2') {
                skipEntry(in, size);
                validateLinkTarget(dest, out, link);
                removeConflictingLeaf(out, false);
                try {
                    Os.symlink(link, out.getAbsolutePath());
                } catch (ErrnoException e) {
                    throw new IOException("cannot create symlink " + out + " -> " + link, e);
                }
                links++;
            } else {
                skipEntry(in, size);
                throw new IOException("unsupported tar entry type " + (char) type + " at " + name);
            }
        }
        return links;
    }

    private static void validateLinkTarget(File dest, File linkFile, String target) throws IOException {
        if (target == null || target.isEmpty() || target.indexOf('\0') >= 0) {
            throw new IOException("empty/invalid tar symlink target at " + linkFile);
        }
        if (target.startsWith("/")) {
            Matcher matcher = EnvPaths.TERMUX_ANY_PREFIX.matcher(target);
            if (!matcher.lookingAt()) {
                throw new IOException("absolute tar symlink escapes env prefix: " + target);
            }
            String remainder = target.substring(matcher.end());
            for (String part : remainder.split("/")) {
                if ("..".equals(part)) {
                    throw new IOException("absolute tar symlink contains path traversal: " + target);
                }
            }
            return;
        }
        File resolved = new File(linkFile.getParentFile(), target);
        if (!PathSafety.isInside(dest, resolved)) {
            throw new IOException("tar symlink escapes env prefix: " + linkFile + " -> " + target);
        }
    }

    private static void ensureParentInside(File dest, File out) throws IOException {
        File parent = out.getParentFile();
        if (parent != null && !PathSafety.isInside(dest, parent)) {
            throw new IOException("tar entry parent escapes env prefix: " + out);
        }
        if (parent != null && !parent.isDirectory() && !parent.mkdirs() && !parent.isDirectory()) {
            throw new IOException("cannot create parent " + parent);
        }
    }

    private static void removeConflictingLeaf(File out, boolean allowDirectory) throws IOException {
        if (TextFileDetector.isSymlink(out)) {
            try {
                Os.remove(out.getAbsolutePath());
            } catch (ErrnoException e) {
                throw new IOException("cannot replace tar symlink " + out, e);
            }
            return;
        }
        if (!out.exists()) {
            return;
        }
        if (out.isDirectory()) {
            if (allowDirectory) {
                return;
            }
            if (!out.delete()) {
                throw new IOException("cannot replace non-empty tar directory " + out);
            }
            return;
        }
        if (!out.delete()) {
            throw new IOException("cannot replace tar file " + out);
        }
    }

    private static byte[] readEntry(InputStream in, long size) throws IOException {
        if (size < 0 || size > (8L << 20)) {
            throw new IOException("unreasonable tar entry size: " + size);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream((int) size);
        copy(in, out, size);
        skipPadding(in, size);
        return out.toByteArray();
    }

    private static void skipEntry(InputStream in, long size) throws IOException {
        skipExactly(in, size);
        skipPadding(in, size);
    }

    private static void writeFile(InputStream in, File out, long size, File dest) throws IOException {
        ensureParentInside(dest, out);
        try (OutputStream os = new FileOutputStream(out)) {
            copy(in, os, size);
        }
        skipPadding(in, size);
    }

    private static void copy(InputStream in, OutputStream out, long size) throws IOException {
        if (size < 0) {
            throw new IOException("negative tar entry size: " + size);
        }
        byte[] buffer = new byte[64 * 1024];
        long remaining = size;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new IOException("truncated tar entry");
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void skipExactly(InputStream in, long size) throws IOException {
        if (size <= 0) {
            return;
        }
        long remaining = size;
        byte[] buffer = new byte[8192];
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new IOException("truncated tar entry");
            }
            remaining -= read;
        }
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long pad = (512 - (size % 512)) % 512;
        skipExactly(in, pad);
    }
}
