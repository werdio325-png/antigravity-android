package com.agy.runtime;

import android.content.Context;

import com.agy.core.CoreEnvironment;
import com.agy.core.CoreRuntimeState;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Unpacks the bundled native runtime (assets/runtime) and web UI (assets/web)
 * into getFilesDir(), verifies the runtime manifest hashes (fail-closed), and
 * describes how to launch the patched core through the glibc loader.
 *
 * targetSdk 28 is required so the loader/core can be exec'd from the data dir.
 */
public final class RuntimeManager {

    public static final String CORE_VERSION = RuntimeConfig.CORE_VERSION;

    private final Context appContext;
    private final String csrfToken;
    private final RuntimePaths paths;
    private final NetworkMonitorHolder network;
    /** Guards against starting the background env install more than once. */
    private final AtomicBoolean envInstallStarted = new AtomicBoolean(false);

    public RuntimeManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.csrfToken = UUID.randomUUID().toString();
        this.paths = new RuntimePaths(context);
        this.network = new NetworkMonitorHolder(context);
    }

    public Context appContext() {
        return appContext;
    }

    public int getHttpsPort() {
        return RuntimeConfig.httpsPort(appContext);
    }

    public String getCsrfToken() {
        return csrfToken;
    }

    public File getRuntimeDir() {
        return paths.getRuntimeDir();
    }

    public File getWebDir() {
        return paths.getWebDir();
    }

    public File getBinDir() {
        return paths.getBinDir();
    }

    public File getLibDir() {
        return paths.getLibDir();
    }

    public File getCertFile() {
        return paths.getCertFile();
    }

    public File getGeminiDir() {
        return paths.getGeminiDir();
    }

    public File getAppDataDir() {
        return paths.getAppDataDir();
    }

    public File getProjectsDir() {
        return paths.getProjectsDir();
    }

    /**
     * Phase 1: extract {@code assets/runtime} (the core + glibc) and the web UI,
     * verify the runtime manifest sha256 (fail-closed), and prepare DNS/onboarding.
     * Idempotent: a runtime whose marker is present and whose manifest still
     * verifies is reused.
     */
    public synchronized void installCore() {
        CoreInstaller.installCore(this);
    }

    /**
     * Phase 2: extract the bionic toolchain and regenerate {@code runtime/bin}
     * bridges on a daemon thread, in parallel with core startup. Best-effort.
     */
    public void installEnvInBackground() {
        EnvInstallScheduler.schedule(this, envInstallStarted);
    }

    /** Loader + flags + env required to boot the patched core. */
    public String[] coreCommand() {
        File bin = getBinDir();
        File lib = getLibDir();
        File loader = new File(bin, RuntimePaths.LOADER_NAME);
        File core = new File(bin, RuntimePaths.CORE_NAME);
        if (!loader.isFile()) {
            throw new IllegalStateException("glibc loader missing: " + loader);
        }
        if (!core.isFile()) {
            throw new IllegalStateException("core binary missing: " + core);
        }

        return new String[]{
                loader.getAbsolutePath(),
                "--library-path", lib.getAbsolutePath(),
                core.getAbsolutePath(),
                "--standalone",
                "--override_ide_name", "antigravity",
                "--subclient_type", "hub",
                "--override_ide_version", CORE_VERSION,
                "--override_user_agent_name", "antigravity",
                "--https_server_port", String.valueOf(getHttpsPort()),
                "--csrf_token", csrfToken,
                // Stable across builds: HOME/.gemini/<APP_DATA_DIR_NAME>.
                "--app_data_dir", RuntimePaths.APP_DATA_DIR_NAME,
                "--web_bundle_path", getWebDir().getAbsolutePath(),
                "--enable_sidecars"
        };
    }

    public void applyEnvironment(ProcessBuilder pb) {
        CoreEnvironment.apply(this, pb);
    }

    // ---- network config ---------------------------------------------------

    /** Start watching the default network; on changes the DNS config is rewritten. */
    public void startNetworkMonitor() {
        network.start();
    }

    /**
     * Refresh DNS config in place. Rewriting resolv.conf is enough for a running
     * core: it uses GODEBUG=netdns=go and re-reads the file, so the process is
     * not restarted.
     */
    public synchronized void refreshNetwork() {
        writeNetworkConfig();
        if (CoreRuntimeState.serverUrl != null) {
            android.util.Log.d("RuntimeManager", "network changed; rewrote DNS config (core stays up)");
        } else {
            android.util.Log.d("RuntimeManager", "network changed; DNS config refreshed");
        }
    }

    public synchronized void writeNetworkConfig() {
        NetworkConfigWriter.write(appContext);
    }
}
