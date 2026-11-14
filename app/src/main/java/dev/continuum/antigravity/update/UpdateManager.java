package dev.continuum.antigravity.update;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.continuum.antigravity.BuildConfig;

/**
 * Orchestrates update checking, downloading and installation.
 *
 * State is published to the web UI as JSON events, so the interface always shows
 * what actually happened (including failures) instead of optimistically claiming
 * an update is available. Downloads only start on explicit user action and the
 * APK is verified against the release checksum before installation is offered.
 */
public final class UpdateManager {

    private static final String TAG = "UpdateManager";
    private static final String PREFS = "continuum_updates";
    private static final String KEY_POSTPONED = "postponed_version";

    public interface Sink {
        void emit(String json);
    }

    private final Context context;
    private final Sink sink;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;
    private final AtomicBoolean busy = new AtomicBoolean(false);

    private String state = "idle";
    private String error;
    private UpdateInfo latest;
    private long downloaded;
    private long total;
    private File downloadedApk;

    public UpdateManager(Context context, Sink sink) {
        this.context = context.getApplicationContext();
        this.sink = sink;
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized String installedVersion() {
        return BuildConfig.APP_VERSION_NAME;
    }

    public synchronized JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            o.put("state", state);
            o.put("installedVersion", BuildConfig.APP_VERSION_NAME);
            o.put("installedVersionCode", BuildConfig.APP_VERSION_CODE);
            o.put("latestVersion", latest != null ? latest.versionName : JSONObject.NULL);
            o.put("latestTag", latest != null ? latest.tagName : JSONObject.NULL);
            o.put("notes", latest != null ? latest.notes : "");
            o.put("sizeBytes", latest != null ? latest.apkSize : 0);
            o.put("sha256", latest != null && latest.hasChecksum() ? latest.sha256 : JSONObject.NULL);
            o.put("publishedAt", latest != null ? latest.publishedAt : "");
            o.put("downloadedBytes", downloaded);
            o.put("totalBytes", total);
            o.put("progress", total > 0 ? (int) Math.min(100, (downloaded * 100) / total) : 0);
            o.put("error", error != null ? error : JSONObject.NULL);
            o.put("postponedVersion", prefs.getString(KEY_POSTPONED, null));
            o.put("needsInstallPermission", UpdateInstaller.needsInstallPermission(context));
            o.put("feedConfigured", UpdateChecker.isConfigured());
            o.put("repo", BuildConfig.UPDATE_REPO);
        } catch (Exception e) {
            Log.w(TAG, "status serialization failed: " + e.getMessage());
        }
        return o;
    }

    private void publish() {
        if (sink != null) {
            sink.emit("window.__agUpdateEvent && window.__agUpdateEvent(" + status().toString() + ");");
        }
    }

    /** Manual check, always allowed to run (bypasses a previous postpone). */
    public void check() {
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        synchronized (this) {
            state = "checking";
            error = null;
        }
        publish();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    UpdateInfo info = UpdateChecker.fetchLatest(context);
                    synchronized (UpdateManager.this) {
                        latest = info;
                        int cmp = UpdateInfo.compareVersions(info.versionName, BuildConfig.APP_VERSION_NAME);
                        state = cmp > 0 ? "available" : "up_to_date";
                    }
                } catch (Exception e) {
                    synchronized (UpdateManager.this) {
                        state = "error";
                        error = friendly(e);
                    }
                } finally {
                    busy.set(false);
                    publish();
                }
            }
        });
    }

    /** Starts (or resumes) the download of the release APK. */
    public void download() {
        final UpdateInfo info;
        synchronized (this) {
            info = latest;
            if (info == null) {
                return;
            }
        }
        if (!busy.compareAndSet(false, true)) {
            return;
        }
        synchronized (this) {
            state = "downloading";
            error = null;
            downloaded = 0;
            total = info.apkSize;
        }
        publish();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    File apk = UpdateDownloader.download(info, context.getCacheDir(),
                            new UpdateDownloader.Progress() {
                                @Override
                                public void onProgress(long done, long size) {
                                    synchronized (UpdateManager.this) {
                                        downloaded = done;
                                        if (size > 0) {
                                            total = size;
                                        }
                                    }
                                    publish();
                                }
                            });
                    synchronized (UpdateManager.this) {
                        downloadedApk = apk;
                        state = "ready";
                    }
                } catch (Exception e) {
                    synchronized (UpdateManager.this) {
                        state = "error";
                        error = friendly(e);
                    }
                } finally {
                    busy.set(false);
                    publish();
                }
            }
        });
    }

    /** Hands the verified APK to the system installer. */
    public void install() {
        final File apk;
        final String expected;
        synchronized (this) {
            apk = downloadedApk;
            expected = latest != null ? latest.sha256 : null;
        }
        if (apk == null) {
            return;
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (!UpdateDownloader.verify(apk, expected)) {
                    synchronized (UpdateManager.this) {
                        state = "error";
                        error = "downloaded file failed checksum verification";
                        downloadedApk = null;
                    }
                    publish();
                    return;
                }
                if (UpdateInstaller.needsInstallPermission(context)) {
                    UpdateInstaller.openInstallPermissionSettings(context);
                    synchronized (UpdateManager.this) {
                        state = "ready";
                        error = "Allow 'Install unknown apps' for Continuum, then press Install again.";
                    }
                    publish();
                    return;
                }
                boolean started = UpdateInstaller.install(context, apk);
                synchronized (UpdateManager.this) {
                    state = started ? "installing" : "error";
                    if (!started) {
                        error = "the system package installer could not be opened";
                    }
                }
                publish();
            }
        });
    }

    /** Remembers the version the user chose to skip; never auto-installs. */
    public void postpone() {
        synchronized (this) {
            if (latest != null) {
                prefs.edit().putString(KEY_POSTPONED, latest.versionName).apply();
            }
            state = "postponed";
        }
        publish();
    }

    public void shutdown() {
        worker.shutdownNow();
    }

    private static String friendly(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) {
            message = e.getClass().getSimpleName();
        }
        return message;
    }
}
