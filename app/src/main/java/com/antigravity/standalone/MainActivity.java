package com.antigravity.standalone;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {
    private static final String TAG = "AntigravityStandalone";
    private static final int PORT = 38696;
    private static final String DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Antigravity/2.11.0";

    private WebView webView;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Process serverProcess = null;
    private volatile boolean isRunning = true;
    private volatile boolean pageLoadedInitially = false;

    @Override
    @SuppressLint("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.parseColor("#101010"));
        window.setNavigationBarColor(Color.parseColor("#101010"));

        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webView);
        webView.setBackgroundColor(Color.parseColor("#101010"));

        setupWebView();
        startUrlRequestListener();
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1001);
                }
            } catch (Exception ignored) {}
        }
        startForegroundEngineService();
        startStandaloneEngine();
    }

    private void startForegroundEngineService() {
        try {
            Intent intent = new Intent(this, EngineService.class);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                startForegroundService(intent);
            } else {
                startService(intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start EngineService", e);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isPortOpen("127.0.0.1", PORT)) {
            String currentUrl = webView != null ? webView.getUrl() : null;
            if (currentUrl == null || !currentUrl.contains("127.0.0.1:" + PORT)) {
                loadAppUrl();
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (isPortOpen("127.0.0.1", PORT)) {
            loadAppUrl();
        }
    }

    @Override
    public void onMultiWindowModeChanged(boolean isInMultiWindowMode, Configuration newConfig) {
        super.onMultiWindowModeChanged(isInMultiWindowMode, newConfig);
        Log.i(TAG, "Windowing mode changed: multiWindow=" + isInMultiWindowMode);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Log.i(TAG, "Configuration changed (orientation/size), keeping engine alive");
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWebView() {
        WebView.setWebContentsDebuggingEnabled(true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);

        settings.setUserAgentString(DESKTOP_UA);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(100);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);

        webView.setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            settings.setOffscreenPreRaster(true);
        }

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.d("AntigravityWeb", cm.message() + " -- line " + cm.lineNumber() + " of " + cm.sourceId());
                return true;
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                WebView.HitTestResult result = view.getHitTestResult();
                String data = result != null ? result.getExtra() : null;
                if (data != null && !data.isEmpty()) {
                    openInBrowser(data);
                    return true;
                }
                WebView tempWebView = new WebView(MainActivity.this);
                tempWebView.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                        if (req != null && req.getUrl() != null) {
                            openInBrowser(req.getUrl().toString());
                        }
                        return true;
                    }
                    @SuppressWarnings("deprecation")
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, String url) {
                        openInBrowser(url);
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(tempWebView);
                resultMsg.sendToTarget();
                return true;
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (request == null || request.getUrl() == null) return false;
                String url = request.getUrl().toString();
                if (url.startsWith("https://127.0.0.1:" + PORT) || url.startsWith("http://127.0.0.1:" + PORT)) {
                    return false;
                }
                openInBrowser(url);
                return true;
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url == null) return false;
                if (url.startsWith("https://127.0.0.1:" + PORT) || url.startsWith("http://127.0.0.1:" + PORT)) {
                    return false;
                }
                openInBrowser(url);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                view.evaluateJavascript(
                    "try {" +
                    "  Object.defineProperty(navigator, 'onLine', { get: () => true, configurable: true });" +
                    "  window.addEventListener('offline', (e) => { e.stopImmediatePropagation(); }, true);" +
                    "} catch (e) {}",
                    null
                );
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.contains("127.0.0.1:" + PORT)) {
                    pageLoadedInitially = true;
                }
                view.evaluateJavascript(
                    "try {" +
                    "  Object.defineProperty(navigator, 'onLine', { get: () => true, configurable: true });" +
                    "  window.dispatchEvent(new Event('online'));" +
                    "} catch (e) {}",
                    null
                );
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (request != null && request.getUrl() != null) {
                    String path = request.getUrl().getPath();
                    if (path != null && path.endsWith("/main.js")) {
                        File patchedJs = getPatchedMainJs();
                        if (patchedJs != null && patchedJs.exists() && patchedJs.length() > 5000000) {
                            try {
                                Map<String, String> headers = new HashMap<>();
                                headers.put("Access-Control-Allow-Origin", "*");
                                headers.put("Cache-Control", "no-cache");
                                return new WebResourceResponse("application/javascript", "UTF-8", 200, "OK", headers, new FileInputStream(patchedJs));
                            } catch (Exception e) {
                                Log.e(TAG, "Error serving patched main.js", e);
                            }
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, android.webkit.WebResourceError error) {
                super.onReceivedError(view, request, error);
                if (request != null && request.isForMainFrame() && isRunning && !pageLoadedInitially) {
                    handler.postDelayed(() -> {
                        if (isRunning && !pageLoadedInitially && isPortOpen("127.0.0.1", PORT)) {
                            loadAppUrl();
                        }
                    }, 1500);
                }
            }
        });
    }

    private synchronized File getPatchedMainJs() {
        File patched = new File(getFilesDir(), "main_mobile_patched.js");
        if (patched.exists() && patched.length() > 5000000) {
            return patched;
        }
        try {
            java.net.URL url = new java.net.URL("http://127.0.0.1:" + PORT + "/main.js");
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (InputStream is = url.openStream()) {
                byte[] buf = new byte[32768];
                int r;
                while ((r = is.read(buf)) != -1) {
                    baos.write(buf, 0, r);
                }
            }
            String content = new String(baos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            content = content.replace(
                "!m||!t||t.size<2?p.push(r):n.has(m.base)||(n.add(m.base),p.push(r))",
                "p.push(r)"
            );
            content = content.replace(
                "rAb=(a,b)=>{if((a=L2(a))&&(b=b.get(a.base))&&!(b.size<2))return{base:a.base,efforts:qAb(b),byEffort:b}}",
                "rAb=(a,b)=>void 0"
            );
            content = content.replace(
                "baseUrl(){return`https://127.0.0.1:${this.port}`}",
                "baseUrl(){return`http://127.0.0.1:${this.port}`}"
            );
            try (FileOutputStream fos = new FileOutputStream(patched)) {
                fos.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            Log.i(TAG, "Patched main.js successfully prepared for mobile touch screen!");
            return patched;
        } catch (Exception e) {
            Log.e(TAG, "Failed to prepare patched main.js", e);
            return null;
        }
    }

    private void openInBrowser(String url) {
        if (url == null || url.trim().isEmpty()) return;
        final String targetUrl = url.trim();
        handler.post(() -> {
            try {
                Log.i(TAG, "Opening external browser for URL: " + targetUrl);
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to launch browser intent for " + targetUrl, e);
                try {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("Antigravity Login URL", targetUrl));
                        android.widget.Toast.makeText(MainActivity.this, "Ссылка скопирована в буфер обмена: " + targetUrl, android.widget.Toast.LENGTH_LONG).show();
                    }
                } catch (Exception ignored) {}
            }
        });
    }

    private void startUrlRequestListener() {
        new Thread(() -> {
            File[] targets = new File[] {
                new File(getCacheDir(), "open_url_request"),
                new File(getFilesDir(), "open_url_request"),
                new File(getApplicationInfo().dataDir, "open_url_request"),
                new File("/data/local/tmp/open_url_request"),
                new File("/sdcard/open_url_request")
            };
            while (isRunning) {
                for (File reqFile : targets) {
                    try {
                        if (reqFile != null && reqFile.exists()) {
                            String url = null;
                            try (BufferedReader reader = new BufferedReader(new FileReader(reqFile))) {
                                url = reader.readLine();
                            }
                            reqFile.delete();
                            if (url != null && !url.trim().isEmpty()) {
                                Log.i(TAG, "Captured open_url_request from " + reqFile.getAbsolutePath() + ": " + url.trim());
                                openInBrowser(url.trim());
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error handling open_url_request", e);
                        try { reqFile.delete(); } catch (Exception ignored) {}
                    }
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {}
            }
        }, "UrlRequestListener").start();
    }

    private void startStandaloneEngine() {
        new Thread(() -> {
            try {
                // 1. Check if server is already responding
                if (isPortOpen("127.0.0.1", PORT)) {
                    loadAppUrl();
                    return;
                }

                File filesDir = getFilesDir();
                File rootfsDir = new File(filesDir, "rootfs");
                File marker = new File(rootfsDir, ".installed_v16");
                if (!marker.exists()) {
                    Log.i(TAG, "Extracting clean embedded rootfs v16 (with Python, Git, cURL, jq, rg, BusyBox)...");
                    deleteRecursively(rootfsDir);
                    extractZipAsset("rootfs.zip", rootfsDir);
                    setExecutableRecursively(rootfsDir);
                    File rishDex = new File(rootfsDir, "bin/rish_shizuku.dex");
                    if (rishDex.exists()) {
                        rishDex.setWritable(false, false);
                    }
                    File busyboxBin = new File(rootfsDir, "bin/busybox");
                    if (busyboxBin.exists()) {
                        try {
                            Process bbProc = new ProcessBuilder(
                                    busyboxBin.getAbsolutePath(),
                                    "--install", "-s",
                                    new File(rootfsDir, "bin").getAbsolutePath()
                            ).start();
                            bbProc.waitFor();
                            Log.i(TAG, "BusyBox symlinks successfully installed in rootfs/bin");
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to install busybox symlinks", e);
                        }
                    }
                    try { marker.createNewFile(); } catch (Exception ignored) {}
                }

                // 2. Setup standalone app etc directory (/data/data/com.antigravity.standalone/etc)
                File appEtcDir = new File(getApplicationInfo().dataDir, "etc");
                appEtcDir.mkdirs();

                StringBuilder resolvSb = new StringBuilder();
                try {
                    android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(android.content.Context.CONNECTIVITY_SERVICE);
                    if (cm != null) {
                        android.net.Network activeNetwork = cm.getActiveNetwork();
                        if (activeNetwork != null) {
                            android.net.LinkProperties lp = cm.getLinkProperties(activeNetwork);
                            if (lp != null) {
                                for (java.net.InetAddress addr : lp.getDnsServers()) {
                                    String host = addr.getHostAddress();
                                    if (host != null && !host.isEmpty() && !host.contains("%")) {
                                        resolvSb.append("nameserver ").append(host).append("\n");
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error querying system DNS", e);
                }
                resolvSb.append("nameserver 8.8.8.8\n");
                resolvSb.append("nameserver 1.1.1.1\n");
                String resolvConfStr = resolvSb.toString();
                writeFile(new File(appEtcDir, "resolv.conf"), resolvConfStr);
                writeFile(new File(rootfsDir, "etc/resolv.conf"), resolvConfStr);

                String nsswitchStr = "hosts: files dns\nnetworks: files\nprotocols: files\nservices: files\nethers: files\nrpc: files\n";
                writeFile(new File(appEtcDir, "nsswitch.conf"), nsswitchStr);
                writeFile(new File(rootfsDir, "etc/nsswitch.conf"), nsswitchStr);

                String hostsStr = "127.0.0.1 localhost\n::1 localhost\n";
                writeFile(new File(appEtcDir, "hosts"), hostsStr);
                writeFile(new File(rootfsDir, "etc/hosts"), hostsStr);

                setExecutableRecursively(appEtcDir);

                // 3. Synchronize configs and AGENTS.md
                File geminiDir = new File(filesDir, ".gemini/antigravity");
                geminiDir.mkdirs();
                File cliDir = new File(filesDir, ".gemini/antigravity-cli");
                cliDir.mkdirs();
                File ideDir = new File(filesDir, ".gemini/antigravity-ide");
                ideDir.mkdirs();
                File rootGeminiDir = new File(filesDir, ".gemini");
                rootGeminiDir.mkdirs();
                File configDir = new File(filesDir, ".gemini/config");
                configDir.mkdirs();
                File projectsDir = new File(configDir, "projects");
                projectsDir.mkdirs();
                try { new File(configDir, ".migrated").createNewFile(); } catch (Exception ignored) {}

                File extConfigDir = new File("/storage/emulated/0/Documents/Antigravity/config");
                if (extConfigDir.exists() && extConfigDir.isDirectory()) {
                    Log.i(TAG, "Syncing configs from Documents/Antigravity/config...");
                    File[] dirsToSync = new File[]{geminiDir, cliDir, ideDir, rootGeminiDir};
                    for (File d : dirsToSync) {
                        copyFileIfExists(new File(extConfigDir, "antigravity_state.pbtxt"), new File(d, "antigravity_state.pbtxt"));
                        copyFileIfExists(new File(extConfigDir, "jetski_state.pbtxt"), new File(d, "jetski_state.pbtxt"));
                        copyFileIfExists(new File(extConfigDir, "settings.json"), new File(d, "settings.json"));
                        copyFileIfExists(new File(extConfigDir, "antigravity-oauth-token"), new File(d, "antigravity-oauth-token"));
                        copyFileIfExists(new File(extConfigDir, "jetski-standalone-oauth-token"), new File(d, "jetski-standalone-oauth-token"));
                        copyFileIfExists(new File(extConfigDir, "installation_id"), new File(d, "installation_id"));
                        copyFileIfExists(new File(extConfigDir, "AGENTS.md"), new File(d, "AGENTS.md"));
                    }
                    copyFileIfExists(new File(extConfigDir, "settings.json"), new File(configDir, "settings.json"));
                    copyFileIfExists(new File(extConfigDir, "AGENTS.md"), new File(filesDir, "AGENTS.md"));
                    copyFileIfExists(new File(extConfigDir, "AGENTS.md"), new File(configDir, "AGENTS.md"));
                    File extProj = new File(extConfigDir, "projects");
                    if (extProj.exists()) {
                        copyFileIfExists(new File(extProj, "outside-of-project.json"), new File(projectsDir, "outside-of-project.json"));
                        copyFileIfExists(new File(extProj, "default-cli-project.json"), new File(projectsDir, "default-cli-project.json"));
                    }
                } else {
                    String statePbtxt = "post_onboarding: {\n" +
                            "  completed_steps: POST_ONBOARDING_STEP_TYPE_MANAGER_WELCOME\n" +
                            "  completed_steps: POST_ONBOARDING_STEP_TYPE_USAGE_MODE\n" +
                            "  completed_steps: POST_ONBOARDING_STEP_TYPE_AGENT_CONFIGURATION\n" +
                            "  completed_steps: POST_ONBOARDING_STEP_TYPE_ADD_WORKSPACE\n" +
                            "}\n" +
                            "seen_nuxs: { uids: 38 uids: 29 uids: 24 uids: 36 uids: 43 uids: 45 }\n" +
                            "agent_onboarding_completed: AGENT_ONBOARDING_STATE_COMPLETED\n" +
                            "last_selected_agent_model: MODEL_PLACEHOLDER_M319\n" +
                            "migrate_convos_into_projects: MIGRATION_STATUS_COMPLETED\n" +
                            "installation_uuid: \"" + java.util.UUID.randomUUID().toString() + "\"\n" +
                            "migrate_retroactive_projects: RETROACTIVE_MIGRATION_STATUS_COMPLETED_UNNECESSARY\n" +
                            "migrations: { key: 3 value: MIGRATION_STATUS_COMPLETED }\n" +
                            "migrations: { key: 4 value: MIGRATION_STATUS_COMPLETED }\n" +
                            "migrations: { key: 5 value: MIGRATION_STATUS_COMPLETED }\n";

                    writeFileIfMissing(new File(geminiDir, "antigravity_state.pbtxt"), statePbtxt);
                    writeFileIfMissing(new File(geminiDir, "jetski_state.pbtxt"), statePbtxt);
                    writeFileIfMissing(new File(geminiDir, "settings.json"), "{\n  \"colorScheme\": \"dark\",\n  \"model\": \"Gemini 3.8 Flash (High)\"\n}\n");
                    writeFileIfMissing(new File(projectsDir, "outside-of-project.json"), "{\n  \"id\": \"outside-of-project\",\n  \"name\": \"Outside of project\"\n}");
                    writeFileIfMissing(new File(projectsDir, "default-cli-project.json"),
                            "{\n  \"id\": \"default-cli-project\",\n  \"name\": \"CLI Project\",\n  \"projectResources\": {}\n}");
                }

                // 4. Native libraries and runtime location
                String nativeDir = getApplicationInfo().nativeLibraryDir;
                File loaderLib = new File(nativeDir, "libldlinux.so");
                File serverBin = ensureUniversalServerBin(new File(nativeDir, "libserver.so"), filesDir);

                File glibcDir = new File(rootfsDir, "lib");
                File binDir = new File(rootfsDir, "bin");
                File etcDir = new File(rootfsDir, "etc");
                File tmpDir = getCacheDir();
                tmpDir.mkdirs();

                // Ensure xdg-open and browser aliases exist in binDir
                File xdgBin = new File(binDir, "xdg-open");
                writeFile(xdgBin, "#!/system/bin/sh\nURL=\"$1\"\n[ -z \"$URL\" ] && shift && URL=\"$*\"\n[ -z \"$URL\" ] && exit 0\n" +
                        "echo \"$URL\" > \"/data/local/tmp/open_url_request\" 2>/dev/null\n" +
                        "echo \"$URL\" > \"" + new File(tmpDir, "open_url_request").getAbsolutePath() + "\" 2>/dev/null\n" +
                        "echo \"$URL\" > \"" + new File(filesDir, "open_url_request").getAbsolutePath() + "\" 2>/dev/null\n" +
                        "echo \"$URL\" > \"/sdcard/open_url_request\" 2>/dev/null\n" +
                        "chmod 666 /data/local/tmp/open_url_request 2>/dev/null\n" +
                        "chmod 666 /sdcard/open_url_request 2>/dev/null\n" +
                        "am start -a android.intent.action.VIEW -d \"$URL\" 2>/dev/null\n" +
                        "for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do\n" +
                        "    if [ ! -f /data/local/tmp/open_url_request ] && [ ! -f \"" + new File(tmpDir, "open_url_request").getAbsolutePath() + "\" ]; then exit 0; fi\n" +
                        "    sleep 0.2 2>/dev/null || sleep 1\n" +
                        "done\nexit 0\n");
                xdgBin.setReadable(true, false);
                xdgBin.setExecutable(true, false);
                for (String alias : new String[]{"x-www-browser", "sensible-browser", "google-chrome", "chromium", "firefox"}) {
                    File aliasBin = new File(binDir, alias);
                    copyFile(xdgBin, aliasBin);
                    aliasBin.setExecutable(true, false);
                }

                boolean lacksAtomics = isCpuLackingAtomics();
                File qemuLib = new File(nativeDir, "libqemu.so");
                if (!qemuLib.exists()) {
                    qemuLib = new File(filesDir, "qemu-aarch64");
                }
                if (!qemuLib.exists()) {
                    qemuLib = new File(binDir, "qemu-aarch64");
                }

                // 5. Start engine process (via QEMU translator if CPU lacks LSE atomics, or bare-metal native if ARMv8.1+)
                ProcessBuilder pb;
                if (lacksAtomics && qemuLib.exists()) {
                    Log.i(TAG, "ARMv8.0 CPU lacking LSE atomics detected. Starting Antigravity engine via universal translator: " + qemuLib.getAbsolutePath());
                    pb = new ProcessBuilder(
                            qemuLib.getAbsolutePath(),
                            "-cpu", "max",
                            loaderLib.getAbsolutePath(),
                            "--library-path", glibcDir.getAbsolutePath() + ":" + nativeDir + ":/system/lib64",
                            serverBin.getAbsolutePath(),
                            "--standalone",
                            "--override_ide_name", "antigravity",
                            "--subclient_type", "hub",
                            "--override_ide_version", "2.11.0",
                            "--override_user_agent_name", "antigravity",
                            "--http_server_port", String.valueOf(PORT),
                            "--csrf_token", "antigravity-standalone-token",
                            "--app_data_dir", "antigravity",
                            "--enable_sidecars"
                    );
                } else {
                    Log.i(TAG, "Starting NATIVE Antigravity engine (NO PROOT): loader=" + loaderLib.getAbsolutePath() + " server=" + serverBin.getAbsolutePath());
                    pb = new ProcessBuilder(
                            loaderLib.getAbsolutePath(),
                            "--library-path", glibcDir.getAbsolutePath() + ":" + nativeDir + ":/system/lib64",
                            serverBin.getAbsolutePath(),
                            "--standalone",
                            "--override_ide_name", "antigravity",
                            "--subclient_type", "hub",
                            "--override_ide_version", "2.11.0",
                            "--override_user_agent_name", "antigravity",
                            "--http_server_port", String.valueOf(PORT),
                            "--csrf_token", "antigravity-standalone-token",
                            "--app_data_dir", "antigravity",
                            "--enable_sidecars"
                    );
                }

                Map<String, String> env = pb.environment();
                env.put("PATH", binDir.getAbsolutePath() + ":/system/bin:/system/xbin:/data/data/com.termux/files/usr/bin");
                env.put("LD_LIBRARY_PATH", glibcDir.getAbsolutePath() + ":" + nativeDir + ":/system/lib64");
                env.put("SHELL", binDir.getAbsolutePath() + "/bash");
                env.put("HOME", filesDir.getAbsolutePath());
                env.put("TMPDIR", tmpDir.getAbsolutePath());
                env.put("SSL_CERT_FILE", etcDir.getAbsolutePath() + "/ssl/certs/ca-certificates.crt");
                env.put("CURL_CA_BUNDLE", etcDir.getAbsolutePath() + "/ssl/certs/ca-certificates.crt");
                env.put("GIT_EXEC_PATH", rootfsDir.getAbsolutePath() + "/lib/git-core");
                env.put("GIT_TEMPLATE_DIR", rootfsDir.getAbsolutePath() + "/share/git-core/templates");
                env.put("PYTHONHOME", rootfsDir.getAbsolutePath() + "/lib/python3.14");
                env.put("PYTHONPATH", rootfsDir.getAbsolutePath() + "/lib/python3.14:" + rootfsDir.getAbsolutePath() + "/lib/python3.14/site-packages:" + rootfsDir.getAbsolutePath() + "/lib/python3.14/lib-dynload");
                env.put("GODEBUG", "netdns=cgo");
                env.put("LANG", "ru_RU.UTF-8");
                env.put("LC_ALL", "ru_RU.UTF-8");
                env.put("TERM", "xterm-256color");

                // Adaptive hardware tuning for low-end, mid-range and flagship devices (RAM & CPU scaling)
                int cores = Runtime.getRuntime().availableProcessors();
                int gomaxprocs = Math.max(2, cores - 2);
                int uvThreads = Math.max(2, Math.min(8, cores / 2));

                long totalMemBytes = 4L * 1024 * 1024 * 1024;
                try {
                    android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                    if (am != null) {
                        android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                        am.getMemoryInfo(mi);
                        totalMemBytes = mi.totalMem;
                    }
                } catch (Exception ignored) {}
                long totalMemGb = totalMemBytes / (1024 * 1024 * 1024);
                int maxArenas = (totalMemGb <= 4) ? 1 : (totalMemGb <= 6 ? 2 : 4);

                env.put("MALLOC_ARENA_MAX", String.valueOf(maxArenas));
                env.put("MALLOC_MMAP_THRESHOLD_", "131072");
                env.put("MALLOC_TRIM_THRESHOLD_", "131072");
                env.put("GOMAXPROCS", String.valueOf(gomaxprocs));
                env.put("UV_THREADPOOL_SIZE", String.valueOf(uvThreads));
                env.put("PYTHONUNBUFFERED", "1");
                env.put("PYTHONDONTWRITEBYTECODE", "1");

                // Geo-bypass: Go net/http respects HTTPS_PROXY natively
                String proxyUrl = readProxyConfig(filesDir, extConfigDir);
                if (proxyUrl != null && !proxyUrl.isEmpty()) {
                    env.put("HTTPS_PROXY", proxyUrl);
                    env.put("HTTP_PROXY", proxyUrl);
                    Log.i(TAG, "Proxy configured: " + proxyUrl);
                }

                pb.redirectErrorStream(true);
                serverProcess = pb.start();

                // Pipe server logs to Android Logcat and persistent server.log
                new Thread(() -> {
                    File logFile = new File(filesDir, "server.log");
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(serverProcess.getInputStream()));
                         java.io.PrintWriter pw = new java.io.PrintWriter(new java.io.FileWriter(logFile, false))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            Log.i("AntigravityServer", line);
                            pw.println(line);
                            pw.flush();
                        }
                    } catch (Exception e) {
                        Log.e("AntigravityServer", "Stream read error", e);
                    }
                }).start();

                new Thread(() -> {
                    try {
                        int exitCode = serverProcess.waitFor();
                        Log.w(TAG, "Server process exited with code: " + exitCode);
                    } catch (Exception ignored) {}
                }, "ServerProcessWatcher").start();

                // 5. Wait for server to become responsive
                int maxAttempts = 60;
                for (int i = 0; i < maxAttempts; i++) {
                    if (isPortOpen("127.0.0.1", PORT)) {
                        Log.i(TAG, "Antigravity native engine is ready!");
                        loadAppUrl();
                        return;
                    }
                    try {
                        int exit = serverProcess.exitValue();
                        Log.e(TAG, "serverProcess died early with exit code: " + exit);
                        showError("Ошибка запуска движка", "Сервер завершил работу с кодом: " + exit);
                        return;
                    } catch (IllegalThreadStateException ignored) {
                        // Still running
                    }
                    try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                }

                if (isPortOpen("127.0.0.1", PORT)) {
                    loadAppUrl();
                } else {
                    Log.e(TAG, "Timeout waiting for Antigravity engine to start.");
                    showError("Инициализация сервиса", "Сервер готовится к работе. Нажмите кнопку ниже для обновления.");
                }

            } catch (Exception e) {
                Log.e(TAG, "Startup failure", e);
                showError("Исключение при запуске", e.getMessage());
            }
        }).start();
    }

    private void showError(String title, String message) {
        handler.post(() -> {
            String html = "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1.0'></head>" +
                    "<body style='background:#101010;color:#f0f0f0;font-family:sans-serif;padding:32px 20px;text-align:center;margin:0;'>" +
                    "<h3 style='color:#ea4335;margin-bottom:12px;'>" + title + "</h3>" +
                    "<p style='color:#9aa0a6;font-size:14px;line-height:1.5;margin-bottom:24px;'>" + message + "</p>" +
                    "<button onclick='location.reload()' style='background:#1a73e8;color:#fff;border:none;padding:12px 28px;border-radius:8px;font-size:15px;font-weight:500;cursor:pointer;'>Повторить</button>" +
                    "</body></html>";
            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
        });
    }

    private String readProxyConfig(File filesDir, File extConfigDir) {
        // Priority: external config > internal
        File[] candidates = {
            new File(extConfigDir, "proxy.txt"),
            new File(filesDir, "proxy.txt")
        };
        for (File f : candidates) {
            if (f != null && f.exists()) {
                try (BufferedReader r = new BufferedReader(new FileReader(f))) {
                    String line = r.readLine();
                    if (line != null && !line.trim().isEmpty()) return line.trim();
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private boolean isCpuLackingAtomics() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/cpuinfo"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("Features")) {
                    return !line.contains("atomics");
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    private File ensureUniversalServerBin(File originalServerBin, File filesDir) {
        try {
            if (originalServerBin == null || !originalServerBin.exists()) {
                return originalServerBin;
            }

            boolean lacksAtomics = isCpuLackingAtomics();

            File binDir = new File(filesDir, "bin");
            binDir.mkdirs();
            File patchedBin = new File(binDir, "libserver.so");

            long offset = 0x6b76bf0L;
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(originalServerBin, "r")) {
                if (raf.length() > offset + 4) {
                    raf.seek(offset);
                    byte[] buf = new byte[4];
                    raf.readFully(buf);
                    boolean hasLseCheck = (buf[0] == (byte)0xfd && buf[1] == (byte)0x7b && buf[2] == (byte)0xbe && buf[3] == (byte)0xa9);

                    if (hasLseCheck && lacksAtomics) {
                        Log.i(TAG, "ARMv8.0 CPU without LSE atomics detected. Preparing universal engine binary...");
                        if (!patchedBin.exists() || patchedBin.length() != originalServerBin.length()) {
                            copyFile(originalServerBin, patchedBin);
                            try (java.io.RandomAccessFile wraf = new java.io.RandomAccessFile(patchedBin, "rw")) {
                                wraf.seek(offset);
                                wraf.write(new byte[]{(byte)0xc0, (byte)0x03, (byte)0x5f, (byte)0xd6});
                            }
                            patchedBin.setReadable(true, false);
                            patchedBin.setExecutable(true, false);
                            Log.i(TAG, "Universal ARMv8.0 patch successfully applied: " + patchedBin.getAbsolutePath());
                        }
                        return patchedBin;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in ensureUniversalServerBin", e);
        }
        return originalServerBin;
    }

    private void writeFile(File file, String content) {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes());
        } catch (Exception e) {
            Log.e(TAG, "Failed writing " + file.getAbsolutePath(), e);
        }
    }

    private void writeFileIfMissing(File file, String content) {
        if (file.exists()) return;
        writeFile(file, content);
    }

    private void copyFileIfExists(File src, File dst) {
        if (src == null || !src.exists()) return;
        copyFile(src, dst);
    }

    private void copyFile(File src, File dst) {
        try (InputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            dst.setReadable(true, false);
            dst.setExecutable(true, false);
        } catch (Exception ignored) {}
    }

    private boolean isPortOpen(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void loadAppUrl() {
        handler.post(() -> webView.loadUrl("http://127.0.0.1:" + PORT + "/"));
    }

    private void extractZipAsset(String assetName, File destDir) throws Exception {
        destDir.mkdirs();
        try (InputStream is = getAssets().open(assetName);
             ZipInputStream zis = new ZipInputStream(is)) {
            ZipEntry entry;
            byte[] buffer = new byte[8192];
            while ((entry = zis.getNextEntry()) != null) {
                File file = new File(destDir, entry.getName());
                if (entry.isDirectory()) {
                    file.mkdirs();
                } else {
                    file.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(file)) {
                        int read;
                        while ((read = zis.read(buffer)) != -1) {
                            fos.write(buffer, 0, read);
                        }
                    }
                    file.setReadable(true, false);
                    file.setExecutable(true, false);
                }
                zis.closeEntry();
            }
        }
    }

    private void setExecutableRecursively(File file) {
        if (file == null || !file.exists()) return;
        file.setReadable(true, false);
        file.setExecutable(true, false);
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    setExecutableRecursively(child);
                }
            }
        }
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        file.delete();
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) {
            isRunning = false;
            try {
                stopService(new Intent(this, EngineService.class));
            } catch (Exception ignored) {}
            if (serverProcess != null) {
                try {
                    serverProcess.destroy();
                } catch (Exception ignored) {}
            }
        }
    }
}
