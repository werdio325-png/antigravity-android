package dev.continuum.antigravity.install;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.util.Log;

import dev.continuum.antigravity.util.AtomicFileWriter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Best-effort installation of declared default packages from the Termux repository. */
public final class SeedPackageInstaller {

    private static final String TAG = "SeedPackageInstaller";
    private static final long RETRY_BACKOFF_MS = TimeUnit.HOURS.toMillis(6);
    private static final long COMMAND_TIMEOUT_MINUTES = 20;
    private static final String STATE_NAME = ".agy-env-seeds.properties";

    private SeedPackageInstaller() {
    }

    /**
     * Installs missing default packages without making the core depend on network
     * availability. Returns true if package contents changed and bridges should refresh.
     */
    public static boolean installDefaults(Context context, File prefix, List<String> packages) {
        if (packages == null || packages.isEmpty()) {
            return false;
        }
        File stateFile = new File(context.getFilesDir(), STATE_NAME);
        Properties state = readState(stateFile);
        File logFile = new File(context.getFilesDir(), "env-seed-install.log");
        if (logFile.isFile() && logFile.length() > (1L << 20)) {
            // Keep diagnostic output bounded across retry attempts.
            logFile.delete();
        }

        boolean changed = false;
        boolean updated = false;
        for (String packageName : packages) {
            if (isPackageAvailable(context, prefix, packageName, logFile)) {
                state.setProperty(packageName + ".status", "ready");
                continue;
            }
            long lastAttempt = parseLong(state.getProperty(packageName + ".lastAttempt"));
            long now = System.currentTimeMillis();
            if (lastAttempt > 0 && now - lastAttempt < RETRY_BACKOFF_MS) {
                Log.i(TAG, packageName + " install deferred by retry backoff");
                continue;
            }
            if (!hasNetwork(context)) {
                state.setProperty(packageName + ".status", "pending-network");
                writeState(stateFile, state);
                Log.i(TAG, packageName + " is a default seed; waiting for a network connection");
                continue;
            }

            if (!updated) {
                if (!runPkg(context, prefix, "update")) {
                    Log.w(TAG, "pkg update failed; aborting seed package installation");
                    break;
                }
                updated = true;
            }

            state.setProperty(packageName + ".lastAttempt", Long.toString(now));
            state.setProperty(packageName + ".status", "installing");
            writeState(stateFile, state);
            boolean installed = runPkg(context, prefix, "install", packageName)
                    && isPackageAvailable(context, prefix, packageName, logFile);
            if (installed) {
                state.setProperty(packageName + ".status", "ready");
                state.remove(packageName + ".lastAttempt");
                changed = true;
                Log.i(TAG, "default package installed: " + packageName);
            } else {
                state.setProperty(packageName + ".status", "pending-retry");
                Log.w(TAG, "default package not ready: " + packageName
                        + "; see " + logFile);
            }
            writeState(stateFile, state);
        }
        return changed;
    }

    private static boolean runPkg(Context context, File prefix, String... args) {
        File pkg = new File(prefix, "bin/pkg");
        if (!pkg.isFile()) {
            Log.w(TAG, "pkg wrapper missing: " + pkg);
            return false;
        }
        List<String> command = new ArrayList<>();
        command.add(pkg.getAbsolutePath());
        for (String arg : args) {
            command.add(arg);
        }
        File logFile = new File(context.getFilesDir(), "env-seed-install.log");
        Process process = null;
        try {
            File home = new File(context.getFilesDir(), "home");
            File tmp = new File(prefix, "tmp");
            home.mkdirs();
            tmp.mkdirs();
            File parent = logFile.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.directory(context.getFilesDir());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
            builder.environment().put("PREFIX", prefix.getAbsolutePath());
            builder.environment().put("HOME", home.getAbsolutePath());
            builder.environment().put("TMPDIR", tmp.getAbsolutePath());
            builder.environment().put("PATH", new File(prefix, "bin").getAbsolutePath() + ":/system/bin");
            builder.environment().put("LD_LIBRARY_PATH", new File(prefix, "lib").getAbsolutePath());
            File preload = new File(prefix, "lib/libtermux-exec.so");
            if (preload.isFile()) {
                builder.environment().put("LD_PRELOAD", preload.getAbsolutePath());
            }
            builder.environment().put("SSL_CERT_FILE", new File(prefix, "etc/tls/cert.pem").getAbsolutePath());
            builder.environment().put("CURL_CA_BUNDLE", new File(prefix, "etc/tls/cert.pem").getAbsolutePath());
            builder.environment().put("OPENSSL_CONF", new File(prefix, "etc/tls/openssl.cnf").getAbsolutePath());
            builder.environment().put("GIT_SSL_CAINFO", new File(prefix, "etc/tls/cert.pem").getAbsolutePath());
            builder.environment().put("AGY_APPLICATION_ID", context.getPackageName());
            builder.environment().put("LANG", "C.UTF-8");
            process = builder.start();
            if (!process.waitFor(COMMAND_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                appendLog(logFile, "\n[AGY] timed out running: " + command + "\n");
                return false;
            }
            int result = process.exitValue();
            if (result != 0) {
                appendLog(logFile, "\n[AGY] exit=" + result + " command=" + command + "\n");
            }
            return result == 0;
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            appendLog(logFile, "\n[AGY] failed command=" + command + " error=" + e + "\n");
            Log.w(TAG, "cannot run pkg " + command + ": " + e.getMessage());
            return false;
        }
    }

    private static boolean isPackageAvailable(Context context, File prefix,
                                              String packageName, File logFile) {
        String executable = "python".equals(packageName) ? "python3" : packageName;
        File binary = new File(prefix, "bin/" + executable);
        if (!binary.isFile()) {
            return false;
        }
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(binary.getAbsolutePath(), "--version");
            builder.directory(context.getFilesDir());
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
            builder.environment().put("PREFIX", prefix.getAbsolutePath());
            builder.environment().put("HOME", new File(context.getFilesDir(), "home").getAbsolutePath());
            builder.environment().put("PATH", new File(prefix, "bin").getAbsolutePath() + ":/system/bin");
            builder.environment().put("LD_LIBRARY_PATH", new File(prefix, "lib").getAbsolutePath());
            File preload = new File(prefix, "lib/libtermux-exec.so");
            if (preload.isFile()) {
                builder.environment().put("LD_PRELOAD", preload.getAbsolutePath());
            }
            process = builder.start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (Exception e) {
            if (process != null) {
                process.destroyForcibly();
            }
            return false;
        }
    }

    private static boolean hasNetwork(Context context) {
        try {
            ConnectivityManager manager = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                return false;
            }
            NetworkInfo info = manager.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception e) {
            Log.w(TAG, "cannot check network state: " + e.getMessage());
            return false;
        }
    }

    private static Properties readState(File file) {
        Properties properties = new Properties();
        if (!file.isFile()) {
            return properties;
        }
        try (FileInputStream input = new FileInputStream(file)) {
            properties.load(input);
        } catch (IOException e) {
            Log.w(TAG, "cannot read seed state: " + e.getMessage());
        }
        return properties;
    }

    private static void writeState(File file, Properties properties) {
        StringBuilder content = new StringBuilder();
        for (String name : properties.stringPropertyNames()) {
            content.append(name).append('=').append(properties.getProperty(name)).append('\n');
        }
        AtomicFileWriter.writeText(file, content.toString());
    }

    private static long parseLong(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void appendLog(File file, String message) {
        try (FileOutputStream output = new FileOutputStream(file, true)) {
            output.write(message.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
    }
}
