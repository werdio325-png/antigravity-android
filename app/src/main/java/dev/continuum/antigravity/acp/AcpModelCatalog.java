package dev.continuum.antigravity.acp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Models and thinking levels discovered from the agent's own session options.
 *
 * A model is only reported AVAILABLE after a successful turn on it: being listed
 * by the runtime proves the runtime offers it, not that Google's servers will run
 * it for this account.
 */
public final class AcpModelCatalog {

    public enum Status {
        /** Listed and exercised successfully for this account. */
        AVAILABLE,
        /** Runtime/CLI does not expose it. */
        NOT_SUPPORTED,
        /** Google rejected it for this account or plan. */
        ACCOUNT_RESTRICTED,
        /** Quota/rate limit reached. */
        QUOTA_RESTRICTED,
        /** Listed but not yet exercised, or classification was not possible. */
        UNKNOWN
    }

    public static final class Entry {
        public final String id;
        public final String label;
        public final String thinkingLevel;
        public final String detail;
        public final Status status;

        Entry(String id, String label, String thinkingLevel, Status status, String detail) {
            this.id = id;
            this.label = label;
            this.thinkingLevel = thinkingLevel;
            this.status = status;
            this.detail = detail;
        }

        Entry with(Status newStatus, String newDetail) {
            return new Entry(id, label, thinkingLevel, newStatus, newDetail);
        }

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("id", id);
                o.put("label", label);
                o.put("thinkingLevel", thinkingLevel == null ? JSONObject.NULL : thinkingLevel);
                o.put("status", status.name().toLowerCase(Locale.US));
                o.put("detail", detail == null ? "" : detail);
            } catch (Exception ignored) {
            }
            return o;
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private String modelOptionId;
    private String thinkingOptionId;
    private final List<String> thinkingLevels = new ArrayList<>();
    private String currentModel;
    private String currentThinking;

    public synchronized List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    public synchronized String modelOptionId() {
        return modelOptionId;
    }

    public synchronized String thinkingOptionId() {
        return thinkingOptionId;
    }

    public synchronized List<String> thinkingLevels() {
        return Collections.unmodifiableList(new ArrayList<>(thinkingLevels));
    }

    public synchronized String currentModel() {
        return currentModel;
    }

    public synchronized JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            JSONArray models = new JSONArray();
            for (Entry entry : entries) {
                models.put(entry.toJson());
            }
            o.put("models", models);
            o.put("modelOptionId", modelOptionId == null ? JSONObject.NULL : modelOptionId);
            o.put("thinkingOptionId", thinkingOptionId == null ? JSONObject.NULL : thinkingOptionId);
            o.put("thinkingLevels", new JSONArray(thinkingLevels));
            o.put("currentModel", currentModel == null ? JSONObject.NULL : currentModel);
            o.put("currentThinking", currentThinking == null ? JSONObject.NULL : currentThinking);
            o.put("source", "acp-session-options");
        } catch (Exception ignored) {
        }
        return o;
    }

    /** Builds the catalogue from a session/new (or session/load) result. */
    public synchronized void applySessionResult(JSONObject result) {
        entries.clear();
        thinkingLevels.clear();
        modelOptionId = null;
        thinkingOptionId = null;
        if (result == null) {
            return;
        }
        JSONArray options = result.optJSONArray("configOptions");
        for (int i = 0; options != null && i < options.length(); i++) {
            JSONObject option = options.optJSONObject(i);
            if (option == null) {
                continue;
            }
            String id = option.optString("id", "");
            String name = option.optString("name", id).toLowerCase(Locale.US);
            JSONArray values = option.optJSONArray("options");
            if (values == null) {
                continue;
            }
            if (isModelOption(id, name)) {
                modelOptionId = id;
                currentModel = option.optString("currentValue", null);
                for (int j = 0; j < values.length(); j++) {
                    JSONObject value = values.optJSONObject(j);
                    if (value == null) {
                        continue;
                    }
                    String modelId = value.optString("value", "");
                    if (modelId.isEmpty()) {
                        continue;
                    }
                    String label = value.optString("name", value.optString("label", modelId));
                    String description = value.optString("description", "");
                    entries.add(new Entry(modelId, label, null, Status.UNKNOWN,
                            description.isEmpty()
                                    ? "Offered by the installed CLI; not yet exercised for this account."
                                    : description));
                }
            } else if (isThinkingOption(id, name)) {
                thinkingOptionId = id;
                currentThinking = option.optString("currentValue", null);
                for (int j = 0; j < values.length(); j++) {
                    JSONObject value = values.optJSONObject(j);
                    if (value != null) {
                        String level = value.optString("value", "");
                        if (!level.isEmpty()) {
                            thinkingLevels.add(level);
                        }
                    }
                }
            }
        }
        mergeThinkingLevels();
    }

    /**
     * The CLI exposes thinking levels as a separate option; when a model encodes
     * its own level (e.g. gemini-4-hydrogen-high), keep it visible per model.
     */
    private void mergeThinkingLevels() {
        if (thinkingLevels.isEmpty()) {
            for (Entry entry : entries) {
                String level = levelFromId(entry.id);
                if (level != null) {
                    if (!thinkingLevels.contains(level)) {
                        thinkingLevels.add(level);
                    }
                }
            }
        }
    }

    static String levelFromId(String id) {
        if (id == null) {
            return null;
        }
        String lower = id.toLowerCase(Locale.US);
        String[] levels = {"xhigh", "high", "medium", "low", "extra", "max"};
        for (String level : levels) {
            if (lower.endsWith("-" + level) || lower.endsWith("_" + level)) {
                return level;
            }
        }
        return null;
    }

    private static boolean isModelOption(String id, String name) {
        String combined = (id + " " + name).toLowerCase(Locale.US);
        // Note: "model" contains "mode", so compare the whole word, not a prefix.
        return combined.contains("model");
    }

    private static boolean isThinkingOption(String id, String name) {
        String combined = (id + " " + name).toLowerCase(Locale.US);
        return combined.contains("thinking") || combined.contains("reasoning")
                || combined.contains("effort");
    }

    /** Records that a turn on this model actually completed. */
    public synchronized void markExercised(String modelId) {
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.id.equals(modelId)) {
                entries.set(i, entry.with(Status.AVAILABLE, "Completed a real response for this account."));
            }
        }
    }

    /**
     * Marks a model with the failure the server actually returned. Classification
     * is conservative: anything unrecognised stays UNKNOWN rather than being
     * reported as available.
     */
    public synchronized void markFailure(String modelId, int code, String message, boolean loginRelated) {
        Status status;
        String detail;
        String text = message == null ? "" : message.toLowerCase(Locale.US);
        if (loginRelated) {
            status = Status.ACCOUNT_RESTRICTED;
            detail = "Authentication required for this account.";
        } else if (text.contains("quota") || text.contains("rate limit") || text.contains("exceeded")
                || text.contains("limit reached") || code == 429) {
            status = Status.QUOTA_RESTRICTED;
            detail = "Account quota or rate limit reached.";
        } else if (text.contains("not available") || text.contains("unsupported")
                || text.contains("unknown model") || text.contains("no such model")) {
            status = Status.NOT_SUPPORTED;
            detail = "The installed CLI or runtime does not expose this model.";
        } else if (text.contains("permission") || text.contains("entitle") || text.contains("plan")
                || text.contains("subscription") || text.contains("region") || code == 403) {
            status = Status.ACCOUNT_RESTRICTED;
            detail = "Google rejected this model for the signed-in account.";
        } else {
            status = Status.UNKNOWN;
            detail = message == null || message.isEmpty()
                    ? "The turn failed and the reason could not be classified."
                    : "Turn failed: " + message;
        }
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.id.equals(modelId)) {
                entries.set(i, entry.with(status, detail));
            }
        }
    }

    /** True when the runtime lists this exact model id. */
    public synchronized boolean contains(String modelId) {
        for (Entry entry : entries) {
            if (entry.id.equals(modelId)) {
                return true;
            }
        }
        return false;
    }
}
