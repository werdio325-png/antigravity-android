package dev.continuum.antigravity.engine;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Iterator;

/**
 * Describes the engine build that is actually installed.
 *
 * <p>{@code engine_info.json} is generated at build time by
 * {@code modules/engine/engine_probe.py} from the embedded {@code language_server}
 * and shipped in {@code assets/runtime/}. It carries the real engine version,
 * build tag and the model catalogue extracted from the binary, so the UI never
 * has to keep a hardcoded model list.</p>
 *
 * <p>It intentionally holds no availability flags: model availability is decided
 * server side for the signed-in account and is only known from the engine's own
 * responses.</p>
 */
public final class EngineInfo {

    private static final String TAG = "EngineInfo";
    private static final String FILE_NAME = "engine_info.json";
    private static volatile JSONObject cached;

    private EngineInfo() {
    }

    /** Parsed engine_info.json, or an empty object when it is unavailable. */
    public static JSONObject read(Context context) {
        JSONObject snapshot = cached;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (EngineInfo.class) {
            if (cached != null) {
                return cached;
            }
            JSONObject loaded = null;
            // Preferred: the verified copy extracted with the runtime.
            try {
                File installed = new File(context.getFilesDir(), "runtime/" + FILE_NAME);
                if (installed.isFile()) {
                    loaded = new JSONObject(readFile(installed));
                }
            } catch (Exception e) {
                Log.w(TAG, "cannot read installed engine_info.json: " + e.getMessage());
            }
            // Fallback: straight from the APK assets (first launch).
            if (loaded == null) {
                try (InputStream in = context.getAssets().open("runtime/" + FILE_NAME)) {
                    loaded = new JSONObject(readStream(in));
                } catch (Exception e) {
                    Log.w(TAG, "engine_info.json missing from assets: " + e.getMessage());
                }
            }
            cached = loaded != null ? loaded : new JSONObject();
            return cached;
        }
    }

    public static String engineVersion(Context context) {
        return read(context).optJSONObject("engine") != null
                ? read(context).optJSONObject("engine").optString("version", "unknown")
                : "unknown";
    }

    public static String buildTag(Context context) {
        JSONObject engine = read(context).optJSONObject("engine");
        return engine != null ? engine.optString("build_tag", "unknown") : "unknown";
    }

    /** The engine's own model catalogue as a JSON array (possibly empty). */
    public static JSONArray models(Context context) {
        JSONObject catalog = read(context).optJSONObject("catalog");
        JSONArray models = catalog != null ? catalog.optJSONArray("models") : null;
        return models != null ? models : new JSONArray();
    }

    /**
     * True when the embedded engine ships this model id, i.e. the model is not
     * missing because the app is too old. It says nothing about availability.
     */
    public static boolean catalogContains(Context context, String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String target = normalize(name);
        JSONArray models = models(context);
        for (int i = 0; i < models.length(); i++) {
            JSONObject model = models.optJSONObject(i);
            if (model == null) {
                continue;
            }
            if (normalize(model.optString("id", "")).equals(target)
                    || normalize(model.optString("display_name", "")).equals(target)) {
                return true;
            }
            Iterator<String> keys = model.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if ("engine_ids".equals(key)) {
                    JSONArray ids = model.optJSONArray(key);
                    for (int j = 0; ids != null && j < ids.length(); j++) {
                        if (normalize(ids.optString(j, "")).equals(target)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    private static String readFile(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            return readStream(in);
        }
    }

    private static String readStream(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toString("UTF-8");
    }
}
