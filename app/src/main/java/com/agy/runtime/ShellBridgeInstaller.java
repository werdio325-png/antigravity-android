package com.agy.runtime;

import android.util.Log;

import com.agy.install.EnvInstaller;
import com.agy.util.ShellQuote;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;

/**
 * Materializes the bionic bridges in getFilesDir()/runtime/bin. Must run after
 * EnvInstaller.install() (it provides $PREFIX/bin/bash). Idempotent; only tools
 * that actually exist at runtime get a bridge (missing tools are skipped).
 *
 * The external-URL openers (xdg-open/open_helper/sensible-browser) are NOT
 * bridged to the prefix: the core execs them to open the OAuth browser and the
 * native OpenUrlWatcher listens for the "AGY_OPEN_URL=" line they print. A
 * bionic bridge would swallow that line and no browser would open.
 */
public final class ShellBridgeInstaller {

    private static final String TAG = "ShellBridgeInstaller";

    private static final String[] OPEN_URL_SHIMS = {
            "xdg-open", "open_helper", "sensible-browser",
    };

    /** Records which prefix the bulk bridges were generated for. Bump when the
     *  bridge script format changes so a warm launch regenerates them once. */
    private static final String BRIDGE_MARKER = ".agy-bridges";
    private static final int BRIDGE_FORMAT = 1;

    private ShellBridgeInstaller() {
    }

    public static synchronized void install(RuntimeManager rm) {
        install(rm, false);
    }

    /**
     * Materializes the bionic bridges. The bulk pass (one script per $PREFIX/bin
     * tool, ~400 files) only runs when the marker is missing/stale or {@code force}
     * is set (fresh env install); a warm launch just refreshes the cheap critical
     * shims.
     */
    public static synchronized void install(RuntimeManager rm, boolean force) {
        File bin = rm.getBinDir();
        if (!bin.isDirectory() && !bin.mkdirs() && !bin.isDirectory()) {
            Log.w(TAG, "cannot create " + bin + "; skipping shell bridges");
            return;
        }
        File prefix = EnvInstaller.prefix(rm.appContext());
        File prefixBash = new File(prefix, "bin/bash");
        if (!prefixBash.isFile()) {
            Log.w(TAG, "prefix bash missing (" + prefixBash + "); skipping shell bridges");
            return;
        }
        writeBridge(new File(bin, "bash"), prefix, "bash");
        writeBridge(new File(bin, "sh"), prefix, "bash");
        // Always keep the AGY opener shim in place (it may have been clobbered
        // by a previous bridge pass or survive from the extracted runtime).
        for (String opener : OPEN_URL_SHIMS) {
            writeOpenUrlShim(new File(bin, opener));
        }
        File[] prefixTools = new File(prefix, "bin").listFiles();
        int toolCount = countFiles(prefixTools);
        File marker = new File(bin, BRIDGE_MARKER);
        if (!force && markerMatches(marker, prefix, toolCount)) {
            return;
        }
        if (prefixTools != null) {
            for (File toolFile : prefixTools) {
                if (toolFile.isFile()) {
                    String name = toolFile.getName();
                    if ("rish".equals(name) || isOpenUrlShim(name)) {
                        continue;
                    }
                    writeBridge(new File(bin, name), prefix, name);
                }
            }
        }
        writeMarker(marker, prefix, toolCount);
    }

    private static int countFiles(File[] files) {
        int n = 0;
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    n++;
                }
            }
        }
        return n;
    }

    private static boolean markerMatches(File marker, File prefix, int toolCount) {
        if (!marker.isFile()) {
            return false;
        }
        try (InputStream in = new FileInputStream(marker)) {
            byte[] buf = new byte[256];
            int n = in.read(buf);
            String content = n > 0 ? new String(buf, 0, n, Charset.forName("UTF-8")) : "";
            return content.equals(markerContent(prefix, toolCount));
        } catch (IOException e) {
            return false;
        }
    }

    private static void writeMarker(File marker, File prefix, int toolCount) {
        try (OutputStream out = new FileOutputStream(marker)) {
            out.write(markerContent(prefix, toolCount).getBytes(Charset.forName("UTF-8")));
        } catch (IOException e) {
            Log.w(TAG, "cannot write bridge marker " + marker + ": " + e.getMessage());
        }
    }

    /** Version + prefix + tool count: any of these changing forces a rebuild. */
    private static String markerContent(File prefix, int toolCount) {
        return BRIDGE_FORMAT + "\n" + prefix.getAbsolutePath() + "\n" + toolCount + "\n";
    }

    private static boolean isOpenUrlShim(String name) {
        for (String opener : OPEN_URL_SHIMS) {
            if (opener.equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes the AGY URL opener shim. It is run by Android's /system/bin/sh,
     * prints the machine-readable "AGY_OPEN_URL=<url>" line (captured by the
     * core stdout bridge) and appends the url to $TMPDIR/agy-open-url for the
     * polling OpenUrlWatcher. Never fails the caller.
     */
    private static void writeOpenUrlShim(File bridge) {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("# AGY URL opener shim (xdg-open / open_helper / sensible-browser).\n");
        sb.append("URL=\"$1\"\n");
        sb.append("[ -z \"$URL\" ] && URL=\"$2\"\n");
        sb.append("[ -z \"$URL\" ] && exit 0\n");
        sb.append("echo \"AGY_OPEN_URL=$URL\"\n");
        sb.append("if [ -n \"$TMPDIR\" ] && [ -d \"$TMPDIR\" ]; then\n");
        sb.append("  printf '%s\\n' \"$URL\" >> \"$TMPDIR/agy-open-url\" 2>/dev/null || true\n");
        sb.append("fi\n");
        sb.append("if command -v termux-open >/dev/null 2>&1; then\n");
        sb.append("  termux-open \"$URL\" >/dev/null 2>&1 && exit 0\n");
        sb.append("fi\n");
        sb.append("if [ -x /system/bin/am ]; then\n");
        sb.append("  /system/bin/am start -a android.intent.action.VIEW -d \"$URL\" >/dev/null 2>&1 && exit 0\n");
        sb.append("fi\n");
        sb.append("exit 0\n");
        writeScript(bridge, sb.toString());
    }

    /**
     * Writes one 755 bridge script. It is run by Android's /system/bin/sh (a
     * POSIX shell, no bionic env needed), which then sets PREFIX/LD_LIBRARY_PATH/
     * LD_PRELOAD/PATH for the bionic child only and execs it.
     */
    private static void writeBridge(File bridge, File prefix, String target) {
        String p = ShellQuote.quote(prefix.getAbsolutePath());
        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("# AGY bridge -> bionic $PREFIX/bin/").append(target).append('\n');
        sb.append("PREFIX=").append(p).append('\n');
        sb.append("export PREFIX\n");
        sb.append("export LD_LIBRARY_PATH=\"$PREFIX/lib\"\n");
        sb.append("export LD_PRELOAD=\"$PREFIX/lib/libtermux-exec.so\"\n");
        sb.append("export PATH=\"$PREFIX/bin:/system/bin\"\n");
        sb.append("export OPENSSL_CONF=\"$PREFIX/etc/tls/openssl.cnf\"\n");
        sb.append("export SSL_CERT_FILE=\"$PREFIX/etc/tls/cert.pem\"\n");
        sb.append("export CURL_CA_BUNDLE=\"$PREFIX/etc/tls/cert.pem\"\n");
        sb.append("exec \"$PREFIX/bin/").append(target).append("\" \"$@\"\n");
        writeScript(bridge, sb.toString());
    }

    private static void writeScript(File bridge, String script) {
        try (OutputStream out = new FileOutputStream(bridge)) {
            out.write(script.getBytes(Charset.forName("UTF-8")));
        } catch (IOException e) {
            Log.w(TAG, "cannot write bridge " + bridge + ": " + e.getMessage());
            return;
        }
        bridge.setReadable(true, false);
        if (!bridge.setExecutable(true, false)) {
            Log.w(TAG, "cannot setExecutable on bridge: " + bridge);
        }
    }
}
