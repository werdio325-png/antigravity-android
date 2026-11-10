package com.agy.gateway.handlers;

import android.content.Context;

import com.agy.BuildConfig;
import com.agy.core.CoreRuntimeState;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

/** Health checks and limited safe repairs for the local runtime environment. */
public final class DoctorHandler {

    private final Context context;

    public DoctorHandler(Context context) {
        this.context = context.getApplicationContext();
    }

    public JSONObject doctor() {
        JSONObject resp = new JSONObject();
        JSONArray checks = new JSONArray();
        int ok = 0, warn = 0, error = 0;
        try {
            File files = context.getFilesDir();
            File logs = new File(files, "logs");
            File usrBash = new File(files, "usr/bin/bash");
            File runtime = new File(files, "runtime");
            File web = new File(files, "web/index.html");
            File gwPort = new File(logs, "gateway.port");
            File gwToken = new File(logs, "gateway.token");
            boolean coreReady = CoreRuntimeState.serverUrl != null && !CoreRuntimeState.serverUrl.trim().isEmpty();
            long freeMb = files.getUsableSpace() / (1024 * 1024);

            error += add(checks, "core_ready", coreReady ? "ok" : (CoreRuntimeState.restarting ? "warn" : "warn"),
                    coreReady ? "Mesin utama siap" : (CoreRuntimeState.restarting ? "Mesin utama sedang mencoba menyala lagi" : "Mesin utama belum siap"),
                    CoreRuntimeState.lastError != null ? ("Pesan terakhir: " + CoreRuntimeState.lastError) : (coreReady ? "Aplikasi siap menerima perintah." : "Tunggu sebentar setelah aplikasi dibuka, atau cek log bila tidak kunjung siap."),
                    "Buka aplikasi, tunggu proses persiapan selesai, lalu cek lagi. Jika berulang, lihat laporan masalah/log.");

            error += add(checks, "usr_env", usrBash.isFile() ? "ok" : "warn",
                    usrBash.isFile() ? "Perlengkapan alat pendukung terpasang" : "Perlengkapan alat pendukung belum lengkap",
                    usrBash.isFile() ? "Alat baris perintah dasar tersedia." : "Sebagian alat mungkin belum siap (biasanya dipasang otomatis saat awal).",
                    "Pastikan aplikasi dibiarkan menyelesaikan persiapan awal. Jika tetap belum lengkap, coba buka ulang aplikasi atau pasang ulang dari APK resmi.");

            warn += add(checks, "runtime", runtime.isDirectory() ? "ok" : "error",
                    runtime.isDirectory() ? "Folder runtime ada" : "Folder runtime tidak ditemukan",
                    runtime.isDirectory() ? "Komponen runtime tersedia." : "Komponen penting aplikasi belum tersedia.",
                    "Pasang ulang aplikasi/APK yang lengkap.");

            warn += add(checks, "web", web.isFile() ? "ok" : "error",
                    web.isFile() ? "Tampilan aplikasi tersedia" : "File tampilan aplikasi tidak ditemukan",
                    web.isFile() ? "Antarmuka web lokal tersedia." : "Antarmuka bisa gagal dimuat.",
                    "Pasang ulang aplikasi/APK yang lengkap.");

            warn += add(checks, "gateway", (gwPort.isFile() && gwToken.isFile()) ? "ok" : "warn",
                    (gwPort.isFile() && gwToken.isFile()) ? "Koneksi layanan lokal siap" : "Koneksi layanan lokal belum lengkap",
                    (gwPort.isFile() && gwToken.isFile()) ? "File penghubung layanan tersedia." : "Layanan mungkin belum aktif atau file penghubung belum dibuat.",
                    "Pastikan aplikasi/layanan sedang berjalan, lalu cek lagi.");

            if (freeMb < 200) {
                error += add(checks, "storage", "error", "Ruang penyimpanan sangat sempit",
                        "Sisa ruang sekitar " + freeMb + " MB.", "Kosongkan ruang penyimpanan HP, lalu coba lagi.");
            } else if (freeMb < 1024) {
                warn += add(checks, "storage", "warn", "Ruang penyimpanan mulai terbatas",
                        "Sisa ruang sekitar " + freeMb + " MB.", "Disarankan sisakan lebih banyak ruang agar pemasangan alat/proses tidak gagal.");
            } else {
                ok += add(checks, "storage", "ok", "Ruang penyimpanan cukup",
                        "Sisa ruang sekitar " + freeMb + " MB.", "");
            }

            ok += add(checks, "logs_dir", (logs.isDirectory() || logs.mkdirs()) ? "ok" : "warn",
                    logs.isDirectory() ? "Folder log siap" : "Folder log bermasalah",
                    logs.getAbsolutePath(), logs.isDirectory() ? "" : "Izinkan aplikasi menyimpan data, atau buka ulang aplikasi.");

            // count levels by re-evaluating (add returns 1 when counted into its bucket in this simple scheme)
            // Recompute accurately:
            ok = 0; warn = 0; error = 0;
            for (int i = 0; i < checks.length(); i++) {
                String lv = checks.getJSONObject(i).optString("level");
                if ("error".equals(lv)) error++;
                else if ("warn".equals(lv)) warn++;
                else ok++;
            }

            resp.put("ok", true);
            resp.put("summary", new JSONObject().put("ok", ok).put("warn", warn).put("error", error));
            resp.put("checks", checks);
            resp.put("app_version", BuildConfig.APP_VERSION_NAME);
        } catch (Exception e) {
            try { resp.put("ok", false); resp.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return resp;
    }

    public JSONObject repair(JSONObject payload) {
        JSONObject resp = new JSONObject();
        JSONArray actions = new JSONArray();
        try {
            File files = context.getFilesDir();
            File logs = new File(files, "logs");
            boolean logsOk = logs.isDirectory() || logs.mkdirs();
            actions.put(new JSONObject().put("action", "ensure_logs_dir").put("ok", logsOk)
                    .put("detail", logsOk ? "Folder log siap." : "Gagal menyiapkan folder log."));

            // Safe temp cleanup in app cache/temp areas only.
            int cleaned = 0;
            File cache = context.getCacheDir();
            if (cache != null && cache.isDirectory()) {
                File[] tmp = cache.listFiles((d, n) -> n != null && (n.endsWith(".tmp") || n.startsWith("tmp")));
                if (tmp != null) {
                    for (File f : tmp) {
                        if (f.isFile() && f.delete()) cleaned++;
                    }
                }
            }
            actions.put(new JSONObject().put("action", "cleanup_cache_tmp").put("ok", true)
                    .put("detail", "File sementara cache dibersihkan: " + cleaned + "."));

            resp.put("ok", true);
            resp.put("actions", actions);
            resp.put("note", "Perbaikan besar (pasang ulang perlengkapan alat/runtime) dilakukan lewat alur aplikasi/instalasi APK, bukan dari perintah ini.");
        } catch (Exception e) {
            try { resp.put("ok", false); resp.put("error", e.getMessage()); } catch (Exception ignored) {}
        }
        return resp;
    }

    private int add(JSONArray arr, String id, String level, String title, String detail, String fix) {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("level", level);
            o.put("title", title);
            o.put("detail", detail);
            if (fix != null && !fix.isEmpty()) o.put("fix", fix);
            arr.put(o);
        } catch (Exception ignored) {}
        return 0;
    }
}
