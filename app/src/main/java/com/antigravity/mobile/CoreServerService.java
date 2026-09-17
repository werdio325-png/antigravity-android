package com.antigravity.mobile;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
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
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class CoreServerService extends Service {
    private static final String TAG = "CoreServer";
    public static final int PORT = 48999;
    public static volatile String serverUrl = null;

    private Process process;
    private PowerManager.WakeLock wakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundNotification();
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Antigravity::WakeLock");
                wakeLock.acquire();
            }
        } catch (Exception ignored) {}

        new Thread(this::runServer, "CoreServerThread").start();
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

            // 3. Command execution
            String csrfToken = UUID.randomUUID().toString();
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
            cmdList.add("2.13.0");
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
            cmdList.add("https://daily-cloudcode-pa.googleapis.com");

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
            File bashBinary = new File(nativeDir, "libbash.so");
            File bashrcFile = new File(etcDir, "bashrc");
            env.put("NATIVE_DIR", nativeDir.getAbsolutePath());
            env.put("SHELL", bashBinary.getAbsolutePath());
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

            process = pb.start();

            // Unified stream reader
            new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String l;
                    while ((l = r.readLine()) != null) {
                        Log.d(TAG, l);
                        if (l.contains("ANTIGRAVITY_OPEN_URL:")) {
                            String authUrl = l.substring(l.indexOf("ANTIGRAVITY_OPEN_URL:") + 21).trim();
                            MainActivity.openCustomTab(getApplicationContext(), authUrl);
                        }
                    }
                } catch (Exception ignored) {}
            }, "ProcessOutput").start();

            // 4. Poll readiness
            long start = System.currentTimeMillis();
            while (System.currentTimeMillis() - start < 25000) {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress("127.0.0.1", PORT), 300);
                    serverUrl = "https://127.0.0.1:" + PORT + "/?csrf_token=" + csrfToken;
                    Log.i(TAG, "Core server ready at: " + serverUrl);
                    break;
                } catch (Exception e) {
                    Thread.sleep(50);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Process error", e);
        }
    }

    private String getDnsConfig() {
        StringBuilder sb = new StringBuilder();
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network net = cm.getActiveNetwork();
                if (net != null) {
                    LinkProperties lp = cm.getLinkProperties(net);
                    if (lp != null) {
                        for (InetAddress a : lp.getDnsServers()) {
                            if (a.getHostAddress() != null) sb.append("nameserver ").append(a.getHostAddress()).append("\n");
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        sb.append("nameserver 8.8.8.8\nnameserver 1.1.1.1\noptions timeout:2 attempts:3\n");
        return sb.toString();
    }

    private void copyAsset(String asset, File dst) {
        dst.getParentFile().mkdirs();
        try (InputStream in = getAssets().open(asset); FileOutputStream out = new FileOutputStream(dst)) {
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            dst.setExecutable(true, false);
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
        f.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(f)) { out.write(s.getBytes()); } catch (Exception ignored) {}
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

    private void startForegroundNotification() {
        String chId = "core_channel";
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(chId, "Core", NotificationManager.IMPORTANCE_LOW);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                new Notification.Builder(this, chId) : new Notification.Builder(this);
        Notification n = b.setContentTitle("Antigravity Mobile").setContentText("Running").setSmallIcon(android.R.drawable.stat_notify_sync).build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1001, n);
        }
    }

    @Override
    public void onDestroy() {
        if (process != null) process.destroy();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
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
