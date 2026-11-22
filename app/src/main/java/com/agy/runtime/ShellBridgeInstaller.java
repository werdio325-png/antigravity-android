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
            "xdg-open", "open", "open_helper", "sensible-browser",
            "x-www-browser", "gnome-open", "google-chrome", "chrome", "chromium"
    };

    /** Records which prefix the bulk bridges were generated for. Bump when the
     *  bridge script format changes so a warm launch regenerates them once. */
    private static final String BRIDGE_MARKER = ".agy-bridges";
    private static final int BRIDGE_FORMAT = 4;

    private ShellBridgeInstaller() {
    }

    public static synchronized void installFallbackBridges(File bin) {
        installFallbackBridges(bin, null);
    }

    public static synchronized void installFallbackBridges(File bin, android.content.Context context) {
        if (bin == null) return;
        if (!bin.isDirectory() && !bin.mkdirs() && !bin.isDirectory()) {
            Log.w(TAG, "cannot create " + bin + "; skipping fallback bridges");
            return;
        }
        // Always guarantee bash and sh exist immediately
        File bash = new File(bin, "bash");
        if (!bash.isFile()) {
            writeScript(bash, "#!/system/bin/sh\n# AGY fallback shell\nexec /system/bin/sh \"$@\"\n");
        }
        File sh = new File(bin, "sh");
        if (!sh.isFile()) {
            writeScript(sh, "#!/system/bin/sh\n# AGY fallback shell\nexec /system/bin/sh \"$@\"\n");
        }
        writeTorchShims(bin, context);
        normalizeRuntimeBinScripts(bin);
        if (context != null) {
            File prefix = EnvInstaller.prefix(context);
            File filesDir = context.getFilesDir();
            String appId = context.getPackageName();
            writeNodeBridge(new File(bin, "node"), prefix, filesDir, appId);
            writeMcpAndPythonBridges(bin, prefix, filesDir, appId);
        }
    }

    /** Strips accidental Windows CRLF from any shell/script shim unpacked into runtime/bin. */
    private static void normalizeRuntimeBinScripts(File bin) {
        File[] children = bin.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (!child.isFile() || child.length() < 4 || child.length() > (1L << 20)) {
                continue;
            }
            try (InputStream in = new FileInputStream(child)) {
                byte[] full = com.agy.util.IoUtil.readAllBytes(in);
                if (full.length < 4 || full[0] != '#' || full[1] != '!') {
                    continue;
                }
                if (!com.agy.install.TextFileDetector.isTextFile(full)) {
                    continue;
                }
                String text = new String(full, java.nio.charset.StandardCharsets.ISO_8859_1);
                if (text.indexOf('\r') < 0) {
                    continue;
                }
                String normalized = text.replace("\r\n", "\n");
                if (!normalized.equals(text)) {
                    writeScript(child, normalized);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static synchronized void writeTorchShims(File bin, android.content.Context context) {
        String pkg = (context != null) ? context.getPackageName() : "com.agy";
        String script = "#!/system/bin/sh\n"
                + "# AGY Torch / Flashlight shim\n"
                + "STATE=\"$1\"\n"
                + "[ -z \"$STATE\" ] && STATE=\"on\"\n"
                + "ENABLE=\"true\"\n"
                + "if [ \"$STATE\" = \"off\" ] || [ \"$STATE\" = \"0\" ] || [ \"$STATE\" = \"false\" ]; then\n"
                + "  ENABLE=\"false\"\n"
                + "fi\n"
                + "if [ -x /system/bin/cmd ]; then\n"
                + "  /system/bin/cmd camera set-torch-mode 0 \"$STATE\" >/dev/null 2>&1 || true\n"
                + "fi\n"
                + "if [ -x /system/bin/am ]; then\n"
                + "  /system/bin/am broadcast -a com.agy.ACTION_TORCH -p " + pkg + " --ez enabled \"$ENABLE\" --es state \"$STATE\" >/dev/null 2>&1 || true\n"
                + "fi\n"
                + "echo \"Flashlight set to $STATE\"\n"
                + "exit 0\n";
        writeScript(new File(bin, "torch"), script);
        writeScript(new File(bin, "termux-torch"), script);
        writeScript(new File(bin, "termux-camera-torch"), script);
    }

    public static synchronized void installOpenUrlShims(File bin) {
        installOpenUrlShims(bin, null);
    }

    public static synchronized void installOpenUrlShims(File bin, android.content.Context context) {
        if (bin == null) return;
        if (!bin.isDirectory() && !bin.mkdirs() && !bin.isDirectory()) {
            Log.w(TAG, "cannot create " + bin + "; skipping open-url shims");
            return;
        }
        for (String opener : OPEN_URL_SHIMS) {
            writeOpenUrlShim(new File(bin, opener), context);
        }
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
        // Always ensure fallback shells and shims exist FIRST
        installFallbackBridges(bin, rm.appContext());
        installOpenUrlShims(bin, rm.appContext());

        File prefix = EnvInstaller.prefix(rm.appContext());
        File prefixBash = new File(prefix, "bin/bash");
        if (!prefixBash.isFile()) {
            Log.w(TAG, "prefix bash missing (" + prefixBash + "); keeping fallback shell bridges");
            return;
        }
        String appId = rm.appContext().getPackageName();
        File filesDir = rm.appContext().getFilesDir();
        writeBridge(new File(bin, "bash"), prefix, "bash", appId);
        writeBridge(new File(bin, "sh"), prefix, "bash", appId);
        writeNodeBridge(new File(bin, "node"), prefix, filesDir, appId);
        writeMcpAndPythonBridges(bin, prefix, filesDir, appId);
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
                    if ("rish".equals(name)
                            || "node".equals(name)
                            || "agy-mcp-android".equals(name)
                            || "agy-mcp-browser".equals(name)
                            || "python3".equals(name)
                            || "python".equals(name)
                            || isOpenUrlShim(name)) {
                        continue;
                    }
                    writeBridge(new File(bin, name), prefix, name, appId);
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
     * Writes the AGY URL opener shim. It prints markers for the core stdout
     * reader and sends one signature-protected internal broadcast as a shell-side fallback.
     */
    private static void writeOpenUrlShim(File bridge, android.content.Context context) {
        String pkg = (context != null) ? context.getPackageName() : "com.agy";

        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("# AGY URL opener shim (xdg-open / open / sensible-browser).\n");
        sb.append("unset LD_PRELOAD\n");
        sb.append("unset LD_LIBRARY_PATH\n");
        sb.append("URL=\"$1\"\n");
        sb.append("[ -z \"$URL\" ] && URL=\"$2\"\n");
        sb.append("[ -z \"$URL\" ] && exit 0\n");
        sb.append("echo \"AGY_OPEN_URL=$URL\"\n");
        sb.append("echo \"ANTIGRAVITY_OPEN_URL: $URL\"\n");
        sb.append("if [ -x /system/bin/am ]; then\n");
        sb.append("  /system/bin/am broadcast -a com.agy.ACTION_OPEN_URL -p ").append(pkg).append(" --es url \"$URL\" >/dev/null 2>&1 || true\n");
        sb.append("fi\n");
        sb.append("exit 0\n");
        writeScript(bridge, sb.toString());
    }

    /**
     * Writes one 755 bridge script. It is run by Android's /system/bin/sh (a
     * POSIX shell, no bionic env needed), which then sets PREFIX/LD_LIBRARY_PATH/
     * LD_PRELOAD/PATH for the bionic child only and execs it.
     */
    private static void writeBridge(File bridge, File prefix, String target, String appId) {
        String p = ShellQuote.quote(prefix.getAbsolutePath());
        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("# AGY bridge -> bionic $PREFIX/bin/").append(target).append('\n');
        sb.append("PREFIX=").append(p).append('\n');
        sb.append("AGY_APPLICATION_ID=").append(ShellQuote.quote(appId)).append('\n');
        sb.append("export PREFIX AGY_APPLICATION_ID\n");
        sb.append("if [ -f \"$PREFIX/lib/libtermux-exec.so\" ]; then\n");
        sb.append("  export LD_PRELOAD=\"$PREFIX/lib/libtermux-exec.so\"\n");
        sb.append("else\n");
        sb.append("  unset LD_PRELOAD\n");
        sb.append("fi\n");
        sb.append("export LD_LIBRARY_PATH=\"$PREFIX/lib\"\n");
        sb.append("export PATH=\"$PREFIX/bin:/system/bin\"\n");
        sb.append("export OPENSSL_CONF=\"$PREFIX/etc/tls/openssl.cnf\"\n");
        sb.append("export SSL_CERT_FILE=\"$PREFIX/etc/tls/cert.pem\"\n");
        sb.append("export CURL_CA_BUNDLE=\"$PREFIX/etc/tls/cert.pem\"\n");
        sb.append("export GIT_CONFIG_NOSYSTEM=1\n");
        sb.append("export GIT_ATTR_NOSYSTEM=1\n");
        sb.append("export GIT_TEMPLATE_DIR=\"$PREFIX/share/git-core/templates\"\n");
        sb.append("export GIT_EXEC_PATH=\"$PREFIX/libexec/git-core\"\n");
        sb.append("export PAGER=\"cat\"\n");
        sb.append("if [ -x \"$PREFIX/bin/").append(target).append("\" ]; then\n");
        sb.append("  exec \"$PREFIX/bin/").append(target).append("\" \"$@\"\n");
        sb.append("else\n");
        sb.append("  exec /system/bin/sh \"$@\"\n");
        sb.append("fi\n");
        writeScript(bridge, sb.toString());
    }

    /**
     * Writes a Bionic bridge for {@code node} used by sidecars (e.g. swarm_board).
     * Runs {@code $PREFIX/bin/node} directly when installed, or performs a locked
     * on-demand {@code pkg update && pkg install nodejs} bootstrap if invoked before
     * Phase 2 seed installation finishes. Preserves caller's {@code HOME} and sidecar
     * environment variables while isolating Bionic {@code LD_*} strictly to the child.
     */
    private static void writeNodeBridge(File bridge, File prefix, File filesDir, String appId) {
        String p = ShellQuote.quote(prefix.getAbsolutePath());
        String homeDir = ShellQuote.quote(filesDir.getAbsolutePath());
        String pkgName = ShellQuote.quote(appId != null ? appId : "com.agy");
        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("# AGY bridge -> bionic $PREFIX/bin/node (with locked auto-bootstrap)\n");
        sb.append("PREFIX=").append(p).append('\n');
        sb.append("HOME_DIR=").append(homeDir).append('\n');
        sb.append("AGY_APPLICATION_ID=").append(pkgName).append('\n');
        sb.append("export PREFIX AGY_APPLICATION_ID\n");
        sb.append("if [ -z \"$HOME\" ]; then export HOME=\"$HOME_DIR\"; fi\n");
        sb.append("if [ -z \"$TMPDIR\" ]; then export TMPDIR=\"$PREFIX/tmp\"; fi\n");
        sb.append("mkdir -p \"$TMPDIR\" 2>/dev/null || true\n");
        sb.append("\n");
        sb.append("run_node() {\n");
        sb.append("  if [ -f \"$PREFIX/lib/libtermux-exec.so\" ]; then\n");
        sb.append("    export LD_PRELOAD=\"$PREFIX/lib/libtermux-exec.so\"\n");
        sb.append("  else\n");
        sb.append("    unset LD_PRELOAD\n");
        sb.append("  fi\n");
        sb.append("  export LD_LIBRARY_PATH=\"$PREFIX/lib\"\n");
        sb.append("  export PATH=\"$PREFIX/bin:$HOME_DIR/runtime/bin:/system/bin\"\n");
        sb.append("  export OPENSSL_CONF=\"$PREFIX/etc/tls/openssl.cnf\"\n");
        sb.append("  export SSL_CERT_FILE=\"$PREFIX/etc/tls/cert.pem\"\n");
        sb.append("  export CURL_CA_BUNDLE=\"$PREFIX/etc/tls/cert.pem\"\n");
        sb.append("  exec \"$PREFIX/bin/node\" \"$@\"\n");
        sb.append("}\n");
        sb.append("\n");
        sb.append("notify_status() {\n");
        sb.append("  TITLE=\"$1\"\n");
        sb.append("  MSG=\"$2\"\n");
        sb.append("  GW_PORT=45160\n");
        sb.append("  if [ -f \"$HOME_DIR/logs/gateway.port\" ]; then\n");
        sb.append("    READ_PORT=$(tr -d '\\r\\n ' < \"$HOME_DIR/logs/gateway.port\" 2>/dev/null)\n");
        sb.append("    [ -n \"$READ_PORT\" ] && GW_PORT=\"$READ_PORT\"\n");
        sb.append("  fi\n");
        sb.append("  if [ -x \"$PREFIX/bin/curl\" ]; then\n");
        sb.append("    (\n");
        sb.append("      export LD_LIBRARY_PATH=\"$PREFIX/lib\"\n");
        sb.append("      unset LD_PRELOAD\n");
        sb.append("      \"$PREFIX/bin/curl\" -sS -m 2 -X POST \"http://127.0.0.1:$GW_PORT/gateway\" \\\n");
        sb.append("        -H \"Content-Type: application/json\" \\\n");
        sb.append("        -d \"{\\\"command\\\":\\\"notify\\\",\\\"payload\\\":{\\\"tag\\\":\\\"agy_node_bootstrap\\\",\\\"id\\\":4150,\\\"title\\\":\\\"$TITLE\\\",\\\"message\\\":\\\"$MSG\\\"}}\" \\\n");
        sb.append("        >/dev/null 2>&1 || true\n");
        sb.append("    )\n");
        sb.append("  elif [ -x \"$PREFIX/bin/agy-notify\" ]; then\n");
        sb.append("    (\n");
        sb.append("      export LD_LIBRARY_PATH=\"$PREFIX/lib\"\n");
        sb.append("      export PATH=\"$PREFIX/bin:/system/bin\"\n");
        sb.append("      \"$PREFIX/bin/agy-notify\" post --tag agy_node_bootstrap -t \"$TITLE\" -m \"$MSG\" >/dev/null 2>&1 || true\n");
        sb.append("    )\n");
        sb.append("  fi\n");
        sb.append("}\n");
        sb.append("\n");
        sb.append("if [ -x \"$PREFIX/bin/node\" ]; then\n");
        sb.append("  run_node \"$@\"\n");
        sb.append("fi\n");
        sb.append("\n");
        sb.append("LOG_DIR=\"$HOME_DIR/logs\"\n");
        sb.append("LOCK_DIR=\"$LOG_DIR/.node-bootstrap.lock\"\n");
        sb.append("LOG_FILE=\"$LOG_DIR/node-bootstrap.log\"\n");
        sb.append("mkdir -p \"$LOG_DIR\" 2>/dev/null || true\n");
        sb.append("\n");
        sb.append("# Wait briefly if env prefix is still extracting on first launch\n");
        sb.append("PKG_WAIT=0\n");
        sb.append("while [ ! -x \"$PREFIX/bin/pkg\" ] && [ \"$PKG_WAIT\" -lt 15 ]; do\n");
        sb.append("  [ -x \"$PREFIX/bin/node\" ] && run_node \"$@\"\n");
        sb.append("  sleep 1\n");
        sb.append("  PKG_WAIT=$((PKG_WAIT + 1))\n");
        sb.append("done\n");
        sb.append("\n");
        sb.append("# Clean up stale lock left behind by a killed process\n");
        sb.append("if [ -d \"$LOCK_DIR\" ] && [ -f \"$LOCK_DIR/pid\" ]; then\n");
        sb.append("  LOCK_PID=$(tr -d '\\r\\n ' < \"$LOCK_DIR/pid\" 2>/dev/null)\n");
        sb.append("  if [ -n \"$LOCK_PID\" ] && ! kill -0 \"$LOCK_PID\" 2>/dev/null; then\n");
        sb.append("    rm -rf \"$LOCK_DIR\" 2>/dev/null || true\n");
        sb.append("  fi\n");
        sb.append("fi\n");
        sb.append("\n");
        sb.append("if mkdir \"$LOCK_DIR\" 2>/dev/null; then\n");
        sb.append("  echo \"$$\" > \"$LOCK_DIR/pid\" 2>/dev/null || true\n");
        sb.append("  trap 'rm -rf \"$LOCK_DIR\" 2>/dev/null || true' EXIT INT TERM\n");
        sb.append("  if [ ! -x \"$PREFIX/bin/node\" ] && [ -x \"$PREFIX/bin/pkg\" ]; then\n");
        sb.append("    notify_status \"Setting up Swarm UI\" \"Installing Node.js runtime...\"\n");
        sb.append("    (\n");
        sb.append("      export HOME=\"$HOME_DIR/home\"\n");
        sb.append("      mkdir -p \"$HOME\" \"$PREFIX/tmp\" 2>/dev/null || true\n");
        sb.append("      export TMPDIR=\"$PREFIX/tmp\"\n");
        sb.append("      if [ -f \"$PREFIX/lib/libtermux-exec.so\" ]; then\n");
        sb.append("        export LD_PRELOAD=\"$PREFIX/lib/libtermux-exec.so\"\n");
        sb.append("      else\n");
        sb.append("        unset LD_PRELOAD\n");
        sb.append("      fi\n");
        sb.append("      export LD_LIBRARY_PATH=\"$PREFIX/lib\"\n");
        sb.append("      export PATH=\"$PREFIX/bin:/system/bin\"\n");
        sb.append("      export OPENSSL_CONF=\"$PREFIX/etc/tls/openssl.cnf\"\n");
        sb.append("      export SSL_CERT_FILE=\"$PREFIX/etc/tls/cert.pem\"\n");
        sb.append("      export CURL_CA_BUNDLE=\"$PREFIX/etc/tls/cert.pem\"\n");
        sb.append("      export LANG=\"C.UTF-8\"\n");
        sb.append("      printf '[%s] on-demand nodejs bootstrap started\\n' \"$(date +%s 2>/dev/null || echo 0)\" >> \"$LOG_FILE\" 2>&1\n");
        sb.append("      \"$PREFIX/bin/pkg\" update >> \"$LOG_FILE\" 2>&1 && \"$PREFIX/bin/pkg\" install nodejs >> \"$LOG_FILE\" 2>&1\n");
        sb.append("    ) || true\n");
        sb.append("    if [ -x \"$PREFIX/bin/node\" ]; then\n");
        sb.append("      notify_status \"Swarm UI Ready\" \"Node.js runtime installed.\"\n");
        sb.append("    else\n");
        sb.append("      notify_status \"Swarm UI Setup Failed\" \"Could not install nodejs. Check network or run: pkg install nodejs\"\n");
        sb.append("    fi\n");
        sb.append("  fi\n");
        sb.append("  rm -rf \"$LOCK_DIR\" 2>/dev/null || true\n");
        sb.append("  trap - EXIT INT TERM\n");
        sb.append("else\n");
        sb.append("  WAITED=0\n");
        sb.append("  while [ -d \"$LOCK_DIR\" ] && [ \"$WAITED\" -lt 120 ]; do\n");
        sb.append("    [ -x \"$PREFIX/bin/node\" ] && break\n");
        sb.append("    sleep 1\n");
        sb.append("    WAITED=$((WAITED + 1))\n");
        sb.append("  done\n");
        sb.append("fi\n");
        sb.append("\n");
        sb.append("if [ -x \"$PREFIX/bin/node\" ]; then\n");
        sb.append("  run_node \"$@\"\n");
        sb.append("fi\n");
        sb.append("echo \"agy node bridge: $PREFIX/bin/node not installed (see $LOG_FILE)\" >&2\n");
        sb.append("exit 127\n");
        writeScript(bridge, sb.toString());
    }

    /**
     * Installs dedicated Bionic bridges for bundled MCP servers and Python so
     * glibc {@code language_server} can spawn them reliably even on first launch
     * and across in-place upgrades without inheriting incompatible {@code LD_*} vars.
     */
    private static void writeMcpAndPythonBridges(File bin, File prefix, File filesDir, String appId) {
        writeExecBridge(new File(bin, "agy-mcp-android"), prefix, filesDir, "agy-mcp-android", null, appId);
        writeExecBridge(new File(bin, "agy-mcp-browser"), prefix, filesDir, "agy-mcp-browser", null, appId);
        writeExecBridge(new File(bin, "python3"), prefix, filesDir, "python3", null, appId);
        writeExecBridge(new File(bin, "python"), prefix, filesDir, "python", "python3", appId);
    }

    private static void writeExecBridge(File bridge, File prefix, File filesDir,
                                        String target, String fallbackTarget, String appId) {
        String p = ShellQuote.quote(prefix.getAbsolutePath());
        String homeDir = ShellQuote.quote(filesDir.getAbsolutePath());
        String pkgName = ShellQuote.quote(appId != null ? appId : "com.agy");
        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("# AGY Bionic exec bridge -> $PREFIX/bin/").append(target).append('\n');
        sb.append("PREFIX=").append(p).append('\n');
        sb.append("HOME_DIR=").append(homeDir).append('\n');
        sb.append("AGY_APPLICATION_ID=").append(pkgName).append('\n');
        sb.append("export PREFIX AGY_APPLICATION_ID\n");
        sb.append("if [ -z \"$HOME\" ]; then export HOME=\"$HOME_DIR\"; fi\n");
        sb.append("if [ -z \"$TMPDIR\" ]; then export TMPDIR=\"$PREFIX/tmp\"; fi\n");
        sb.append("mkdir -p \"$TMPDIR\" 2>/dev/null || true\n");
        sb.append("if [ -f \"$PREFIX/lib/libtermux-exec.so\" ]; then\n");
        sb.append("  export LD_PRELOAD=\"$PREFIX/lib/libtermux-exec.so\"\n");
        sb.append("else\n");
        sb.append("  unset LD_PRELOAD\n");
        sb.append("fi\n");
        sb.append("export LD_LIBRARY_PATH=\"$PREFIX/lib\"\n");
        sb.append("export PATH=\"$PREFIX/bin:$HOME_DIR/runtime/bin:/system/bin\"\n");
        sb.append("export OPENSSL_CONF=\"$PREFIX/etc/tls/openssl.cnf\"\n");
        sb.append("export SSL_CERT_FILE=\"$PREFIX/etc/tls/cert.pem\"\n");
        sb.append("export CURL_CA_BUNDLE=\"$PREFIX/etc/tls/cert.pem\"\n");
        sb.append("export LANG=\"C.UTF-8\"\n");
        sb.append("WAIT=0\n");
        sb.append("while [ ! -x \"$PREFIX/bin/").append(target).append("\" ]");
        if (fallbackTarget != null) {
            sb.append(" && [ ! -x \"$PREFIX/bin/").append(fallbackTarget).append("\" ]");
        }
        sb.append(" && [ \"$WAIT\" -lt 10 ]; do\n");
        sb.append("  sleep 1\n");
        sb.append("  WAIT=$((WAIT + 1))\n");
        sb.append("done\n");
        sb.append("if [ -x \"$PREFIX/bin/").append(target).append("\" ]; then\n");
        sb.append("  exec \"$PREFIX/bin/").append(target).append("\" \"$@\"\n");
        sb.append("fi\n");
        if (fallbackTarget != null) {
            sb.append("if [ -x \"$PREFIX/bin/").append(fallbackTarget).append("\" ]; then\n");
            sb.append("  exec \"$PREFIX/bin/").append(fallbackTarget).append("\" \"$@\"\n");
            sb.append("fi\n");
        }
        sb.append("echo \"agy bridge: $PREFIX/bin/").append(target).append(" not found\" >&2\n");
        sb.append("exit 127\n");
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
        bridge.setExecutable(true, false);
        try {
            android.system.Os.chmod(bridge.getAbsolutePath(), 0755);
        } catch (Throwable ignored) {
        }
    }
}
