package com.antigravity.mobile.dev;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class CoreServerService extends Service {
    private static final String TAG = "CoreServer";
    public static final String ENGINE_VERSION = "2.13.0";
    public static int PORT = 49000;
    public static volatile String csrfToken = null;
    public static volatile String serverUrl = null;

    private Process process;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        if (!"com.antigravity.mobile.dev".equals(getPackageName())) {
            PORT = 48000;
        }
        startForegroundNotification();
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AntigravityDev::WakeLock");
                wakeLock.acquire();
            }
        } catch (Exception ignored) {}

        new Thread(this::runServer, "CoreServerThread").start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    private void runServer() {
        try {
            File filesDir = getFilesDir();
            File nativeDir = new File(getApplicationInfo().nativeLibraryDir);
            File runtimeDir = new File(filesDir, "runtime");
            File certsDir = new File(runtimeDir, "certs");
            File binDir = new File(runtimeDir, "bin");
            File etcDir = new File(runtimeDir, "etc");
            File appDataDir = new File(filesDir, ".gemini/antigravity");
            File projectsDir = new File(filesDir, ".gemini/config/projects");

            certsDir.mkdirs();
            binDir.mkdirs();
            etcDir.mkdirs();
            appDataDir.mkdirs();
            projectsDir.mkdirs();

            // 1. Assets setup
            File caCert = new File(certsDir, "ca-certificates.crt");
            if (!caCert.exists()) copyAsset("runtime/certs/ca-certificates.crt", caCert);
            if (!new File(appDataDir, "antigravity_state.pbtxt").exists()) copyAssetDirIfMissing("runtime/seed", appDataDir);
            if (!new File(etcDir, "bashrc").exists()) copyAssetDirIfMissing("runtime/etc", etcDir);

            // 1.1 Fast tools copy (only if missing to avoid heavy I/O on every launch)
            File markerFile = new File(binDir, ".tools_installed");
            if (!markerFile.exists()) {
                copyAssetDirIfMissing("runtime/tools", binDir);
                File[] tools = binDir.listFiles();
                if (tools != null) {
                    for (File t : tools) {
                        t.setExecutable(true, false);
                        t.setReadable(true, false);
                    }
                }
                try { markerFile.createNewFile(); } catch (Exception ignored) {}
            }

            // 1.2 Python runtime setup
            File pyDir = new File(runtimeDir, "python");
            if (!new File(pyDir, "os.py").exists()) {
                pyDir.mkdirs();
                File stdlibZip = new File(pyDir, "stdlib.zip");
                copyAsset("runtime/python/stdlib.zip", stdlibZip);
                if (stdlibZip.exists() && stdlibZip.length() > 0) {
                    try {
                        extractZip(stdlibZip, pyDir);
                        stdlibZip.delete();
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to extract python stdlib", e);
                    }
                }
            }

            // Default empty project file
            File projFile = new File(projectsDir, "outside-of-project.json");
            if (!projFile.exists()) writeString(projFile, "{}");

            // 2. DNS config: patched language_server reads etc//resolv.conf in CWD (filesDir)
            writeString(new File(filesDir, "etc/resolv.conf"), getDnsConfig());

            // Pre-create D-Bus keyring bypass marker
            try {
                File cacheDir = new File(filesDir, ".gemini/cache");
                cacheDir.mkdirs();
                new File(cacheDir, "antigravity-keyring-unavailable").createNewFile();
            } catch (Exception ignored) {}

            // 3. Command execution
            csrfToken = UUID.randomUUID().toString();
            File binary = new File(nativeDir, "liblanguage_server.so");
            File linker = new File(nativeDir, "ld-linux-aarch64.so.1");
            File lseEmulator = new File(nativeDir, "liblse_emulator.so");
            boolean hardwareAtomics = hasHardwareAtomics();
            boolean useEmulator = !hardwareAtomics && lseEmulator.exists();
            Log.i(TAG, "CPU hardware atomics: " + hardwareAtomics + ", useEmulator: " + useEmulator);

            java.util.List<String> cmdList = new java.util.ArrayList<>();
            cmdList.add(linker.getAbsolutePath());
            cmdList.add("--library-path");
            cmdList.add(nativeDir.getAbsolutePath() + ":/system/lib64");
            if (useEmulator) {
                cmdList.add("--preload");
                cmdList.add(lseEmulator.getAbsolutePath());
            }
            cmdList.add(binary.getAbsolutePath());
            cmdList.add("--standalone");
            cmdList.add("--override_ide_name");
            cmdList.add("antigravity");
            cmdList.add("--subclient_type");
            cmdList.add("hub");
            cmdList.add("--override_ide_version");
            cmdList.add(ENGINE_VERSION);
            cmdList.add("--override_user_agent_name");
            cmdList.add("antigravity");
            cmdList.add("--https_server_port");
            cmdList.add(String.valueOf(PORT));
            cmdList.add("--csrf_token");
            cmdList.add(csrfToken);
            cmdList.add("--app_data_dir");
            cmdList.add("antigravity");
            cmdList.add("--api_server_url");
            cmdList.add("https://generativelanguage.googleapis.com");
            cmdList.add("--cloud_code_endpoint");
            cmdList.add("https://cloudcode-pa.googleapis.com");
            cmdList.add("--use_ls_chrome_devtools_mcp=false");
            cmdList.add("--disable_telemetry=true");

            String[] cmd = cmdList.toArray(new String[0]);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(filesDir);
            pb.redirectErrorStream(true); // Combine stdout & stderr
            Map<String, String> env = pb.environment();
            env.put("HOME", filesDir.getAbsolutePath());
            env.put("TMPDIR", getCacheDir().getAbsolutePath());
            env.put("SSL_CERT_FILE", caCert.getAbsolutePath());
            env.put("SSL_CERT_DIR", certsDir.getAbsolutePath());
            env.put("RESOLV_CONF", new File(filesDir, "etc/resolv.conf").getAbsolutePath());
            File bashScript = new File(binDir, "bash");
            File bashrcFile = new File(etcDir, "bashrc");
            env.put("NATIVE_DIR", nativeDir.getAbsolutePath());
            env.put("SHELL", bashScript.getAbsolutePath());
            env.put("BASH_ENV", bashrcFile.getAbsolutePath());
            env.put("ENV", bashrcFile.getAbsolutePath());
            env.put("PYTHONHOME", pyDir.getAbsolutePath());
            env.put("PYTHONPATH", pyDir.getAbsolutePath() + ":" + new File(pyDir, "lib-dynload").getAbsolutePath());
            env.put("PATH", nativeDir.getAbsolutePath() + ":" + binDir.getAbsolutePath() + ":/system/bin:/system/xbin");
            env.put("LD_LIBRARY_PATH", nativeDir.getAbsolutePath() + ":/system/lib64");
            if (useEmulator) {
                env.put("LD_PRELOAD", lseEmulator.getAbsolutePath());
            }
            env.put("ANTIGRAVITY_VSCODE_HOST", "1");
            env.put("LANG", "C.UTF-8");
            env.put("LC_ALL", "C.UTF-8");

            PerfLogger.log("CoreService: starting language_server process");
            process = pb.start();
            PerfLogger.log("CoreService: pb.start() completed");

            // Unified stream reader
            new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String l;
                    while ((l = r.readLine()) != null) {
                        Log.d(TAG, l);
                        PerfLogger.log("LS: " + l);
                        if (l.contains("ANTIGRAVITY_OPEN_URL:")) {
                            String authUrl = l.substring(l.indexOf("ANTIGRAVITY_OPEN_URL:") + 21).trim();
                            Log.i(TAG, "[Auth] Captured OAuth URL from core: " + authUrl);
                            PerfLogger.log("[Auth] Captured OAuth URL from core: " + authUrl);
                            UrlRouter.openCustomTab(getApplicationContext(), authUrl);
                        } else if (l.contains("Auth succeeded")) {
                            Log.i(TAG, "[Auth] Core authentication succeeded! Features & managers refreshing.");
                            PerfLogger.log("[Auth] Core authentication succeeded! Features & managers refreshing.");
                        } else if (l.contains("loadCodeAssist")) {
                            Log.i(TAG, "[Auth] CodeAssist loaded: " + l);
                            PerfLogger.log("[Auth] CodeAssist loaded: " + l);
                        } else if (l.contains("fetchAvailableModels")) {
                            Log.i(TAG, "[Models] Language server fetched available models: " + l);
                            PerfLogger.log("[Models] Language server fetched available models: " + l);
                        } else if (l.contains("projects_migration.go") || l.contains("Projects migration")) {
                            Log.i(TAG, "[Projects] Migration event: " + l);
                            PerfLogger.log("[Projects] Migration event: " + l);
                        } else if (l.contains("ANTIGRAVITY_NOTIFY:")) {
                            String payload = l.substring(l.indexOf("ANTIGRAVITY_NOTIFY:") + 19).trim();
                            String title = "Antigravity Dev";
                            String msg = payload;
                            int sep = payload.indexOf("|");
                            if (sep != -1) {
                                title = payload.substring(0, sep).trim();
                                msg = payload.substring(sep + 1).trim();
                            }
                            showUserNotification(title, msg);
                        }
                    }
                } catch (Exception ignored) {}
            }, "ProcessOutput").start();

            // 4. Poll HTTPS server readiness via local loopback socket
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 25000) {
                if (process != null && !process.isAlive()) {
                    Log.e(TAG, "CoreServer process exited prematurely with code: " + process.exitValue());
                    PerfLogger.log("CoreService: process died with exit code: " + process.exitValue());
                    break;
                }
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress("127.0.0.1", PORT), 200);
                    serverUrl = "https://127.0.0.1:" + PORT + "/?csrf_token=" + csrfToken;
                    PerfLogger.log("CoreService: Socket ready! serverUrl set: " + serverUrl);
                    Log.i(TAG, "Core server ready at: " + serverUrl);
                    updateNotificationStatus("Готов к работе (Порт " + PORT + ")");
                    break;
                } catch (Exception ignored) {
                    try { Thread.sleep(40); } catch (Exception ignored2) {}
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Process error", e);
        }
    }

    private String getDnsConfig() {
        StringBuilder sb = new StringBuilder();
        boolean hasActiveDns = false;
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network net = cm.getActiveNetwork();
                if (net != null) {
                    LinkProperties lp = cm.getLinkProperties(net);
                    if (lp != null) {
                        for (InetAddress a : lp.getDnsServers()) {
                            if (a instanceof java.net.Inet4Address && a.getHostAddress() != null) {
                                sb.append("nameserver ").append(a.getHostAddress()).append("\n");
                                hasActiveDns = true;
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        // Public fallback resolvers
        sb.append("nameserver 8.8.8.8\nnameserver 1.1.1.1\noptions timeout:1 attempts:2\n");
        return sb.toString();
    }

    private void copyAsset(String asset, File dst) {
        File parent = dst.getParentFile();
        if (parent != null) parent.mkdirs();
        try (InputStream in = getAssets().open(asset); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            if (asset.startsWith("runtime/tools") || (dst.getParentFile() != null && dst.getParentFile().getName().equals("bin"))) {
                dst.setExecutable(true, false);
            }
        } catch (Exception ignored) {}
    }

    private void copyAssetDir(String dir, File target) {
        try {
            String[] list = getAssets().list(dir);
            if (list == null) return;
            target.mkdirs();
            for (String f : list) copyAsset(dir + "/" + f, new File(target, f));
        } catch (Exception ignored) {}
    }

    private void copyAssetDirIfMissing(String dir, File target) {
        try {
            String[] list = getAssets().list(dir);
            if (list == null) return;
            target.mkdirs();
            for (String f : list) {
                File dst = new File(target, f);
                if (!dst.exists() || dst.length() == 0) {
                    copyAsset(dir + "/" + f, dst);
                }
            }
        } catch (Exception ignored) {}
    }

    private void writeString(File f, String s) {
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    private void extractZip(File zipFile, File targetDir) throws IOException {
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            byte[] buf = new byte[16384];
            while ((entry = zis.getNextEntry()) != null) {
                File out = new File(targetDir, entry.getName());
                if (entry.isDirectory()) {
                    out.mkdirs();
                } else {
                    out.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        int r;
                        while ((r = zis.read(buf)) != -1) fos.write(buf, 0, r);
                    }
                    out.setReadable(true, false);
                }
                zis.closeEntry();
            }
        }
    }

    public static final String CH_CORE = "core_channel_dev";
    public static final String CH_NOTIFY = "antigravity_notifications_dev";

    private void startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel chCore = new NotificationChannel(CH_CORE, "Служба Antigravity Dev", NotificationManager.IMPORTANCE_LOW);
            chCore.setDescription("Фоновая служба ядра Antigravity Dev");
            nm.createNotificationChannel(chCore);

            NotificationChannel chNotify = new NotificationChannel(CH_NOTIFY, "Уведомления Antigravity Dev", NotificationManager.IMPORTANCE_HIGH);
            chNotify.setDescription("Уведомления от ИИ-ассистента Antigravity Dev");
            chNotify.enableVibration(true);
            nm.createNotificationChannel(chNotify);
        }

        Notification n = buildServiceNotification("Запуск ядра Dev...");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1001, n);
        }
    }

    private Notification buildServiceNotification(String status) {
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                new Notification.Builder(this, CH_CORE) : new Notification.Builder(this);

        return b.setContentTitle("Antigravity Mobile Dev")
                .setContentText(status)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotificationStatus(String status) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(1001, buildServiceNotification(status));
            }
        } catch (Exception ignored) {}
    }

    private void showUserNotification(String title, String message) {
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm == null) return;

            Intent openApp = new Intent(this, MainActivity.class);
            openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, (int) System.currentTimeMillis(), openApp,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0));

            Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                    new Notification.Builder(this, CH_NOTIFY) : new Notification.Builder(this);

            Notification n = b.setContentTitle(title)
                    .setContentText(message)
                    .setStyle(new Notification.BigTextStyle().bigText(message))
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentIntent(pi)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .setDefaults(Notification.DEFAULT_ALL)
                    .build();

            int notifyId = (int) (System.currentTimeMillis() % 100000) + 2000;
            nm.notify(notifyId, n);
        } catch (Exception e) {
            Log.e(TAG, "Failed to show user notification", e);
        }
    }

    @Override
    public void onDestroy() {
        if (process != null) {
            process.destroy();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (Exception ignored) {
                    process.destroyForcibly();
                }
            }
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            try { wakeLock.release(); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    private boolean hasHardwareAtomics() {
        try (BufferedReader reader = new BufferedReader(new java.io.FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Features")) {
                    for (String feat : line.split("\\s+")) {
                        if ("atomics".equalsIgnoreCase(feat)) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read /proc/cpuinfo: " + e.getMessage());
        }
        return false;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
