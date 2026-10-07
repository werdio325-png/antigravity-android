package com.agy.core;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.agy.oauth.OAuthManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * The core execs core/tools/xdg-open, but swallows its stdout, so the
 * "AGY_OPEN_URL=" line never reaches the stdout bridge. The shim's fallback
 * file is the only surviving channel: tail it for new URLs.
 */
public final class OpenUrlWatcher {

    private static final String TAG = "OpenUrlWatcher";
    private static final String OPEN_URL_PREFIX = "AGY_OPEN_URL=";
    /** Fallback channel written by core/tools/xdg-open (append-only). */
    private static final String OPEN_URL_FILE = "agy-open-url";
    private static final long OPEN_URL_POLL_MS = 500;

    private final Context appContext;
    private final OAuthManager oauth = new OAuthManager();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean stopping;
    /** Lines already handled in the fallback file; seeded on start. */
    private volatile int openUrlsSeen = 0;
    private Thread watcher;

    public OpenUrlWatcher(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** Seeds the file offset then starts the daemon tail thread. */
    public void start() {
        openUrlsSeen = readLines().size();
        Log.i(TAG, "open-url watcher seeded at " + openUrlsSeen + " line(s)");
        watcher = new Thread(this::watchFile, "CoreOpenUrlWatch");
        watcher.setDaemon(true);
        watcher.start();
    }

    public void stop() {
        stopping = true;
    }

    /** Detects the stdout OPEN_URL prefix; called by the stream reader. */
    public void handleStreamLine(String line) {
        if (line == null) {
            return;
        }
        int at = line.indexOf(OPEN_URL_PREFIX);
        if (at >= 0) {
            String url = line.substring(at + OPEN_URL_PREFIX.length()).trim();
            if (!url.isEmpty()) {
                openUrl(url);
            }
        }
    }

    private File openUrlFile() {
        return new File(appContext.getCacheDir(), OPEN_URL_FILE);
    }

    /** Non-empty lines currently in the fallback opener file (append-only). */
    private List<String> readLines() {
        List<String> lines = new ArrayList<>();
        File file = openUrlFile();
        if (!file.isFile()) {
            return lines;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), Charset.forName("UTF-8")))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot read " + OPEN_URL_FILE, e);
        }
        return lines;
    }

    /** Polls the fallback file and opens URLs appended after service start. */
    private void watchFile() {
        while (!stopping) {
            List<String> lines = readLines();
            if (lines.size() < openUrlsSeen) {
                // Truncated/rewritten: resync without replaying history.
                openUrlsSeen = lines.size();
            } else if (lines.size() > openUrlsSeen) {
                int fresh = lines.size() - openUrlsSeen;
                Log.i(TAG, "open-url file: " + fresh + " new URL(s)");
                for (int i = openUrlsSeen; i < lines.size(); i++) {
                    openUrl(lines.get(i));
                }
                openUrlsSeen = lines.size();
            }
            sleep(OPEN_URL_POLL_MS);
        }
    }

    /**
     * The core shells out to core/tools/xdg-open, whose shim prints
     * "AGY_OPEN_URL=<url>" to stdout. The core process runs as the app UID, so
     * `am` is unavailable: this bridge fires the ACTION_VIEW intent on the main
     * thread instead.
     */
    private void openUrl(final String url) {
        if (url == null || url.trim().isEmpty()) {
            return;
        }
        Log.i(TAG, "open-url -> external browser: " + url);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                boolean opened = oauth.openInBrowser(appContext, url);
                if (!opened) {
                    Log.w(TAG, "no browser accepted the url: " + url);
                }
            }
        });
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
