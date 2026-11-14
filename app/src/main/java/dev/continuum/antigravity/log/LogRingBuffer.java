package dev.continuum.antigravity.log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * High-performance thread-safe circular ring buffer for execution logs and telemetry.
 */
public final class LogRingBuffer {

    public static final int DEFAULT_CAPACITY = 500;

    public static final class LogEntry {
        public final long timestamp;
        public final String channel;
        public final String level;
        public final String text;

        public LogEntry(long timestamp, String channel, String level, String text) {
            this.timestamp = timestamp;
            this.channel = channel != null ? channel : "core";
            this.level = level != null ? level : "INFO";
            this.text = text != null ? text : "";
        }

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("time", timestamp);
                obj.put("channel", channel);
                obj.put("level", level);
                obj.put("msg", text);
            } catch (JSONException ignored) {
            }
            return obj;
        }
    }

    private final int capacity;
    private final LinkedList<LogEntry> buffer = new LinkedList<>();

    public LogRingBuffer() {
        this(DEFAULT_CAPACITY);
    }

    public LogRingBuffer(int capacity) {
        this.capacity = Math.max(50, capacity);
    }

    public synchronized void append(String channel, String level, String text) {
        if (buffer.size() >= capacity) {
            buffer.removeFirst();
        }
        buffer.addLast(new LogEntry(System.currentTimeMillis(), channel, level, text));
    }

    public synchronized void appendLine(String line) {
        if (line == null || line.trim().isEmpty()) {
            return;
        }
        String channel = "core";
        String level = "INFO";
        if (line.contains("[ERROR]") || line.contains("ERROR:") || line.startsWith("E ")) {
            level = "ERROR";
        } else if (line.contains("[WARN]") || line.contains("WARNING:") || line.startsWith("W ")) {
            level = "WARN";
        } else if (line.contains("[DEBUG]") || line.startsWith("D ")) {
            level = "DEBUG";
        }
        append(channel, level, line);
    }

    public synchronized List<LogEntry> getRecent(int maxCount) {
        int count = Math.min(Math.max(1, maxCount), buffer.size());
        int start = buffer.size() - count;
        List<LogEntry> result = new ArrayList<>(count);
        for (int i = start; i < buffer.size(); i++) {
            result.add(buffer.get(i));
        }
        return result;
    }

    public synchronized List<LogEntry> getSince(long sinceTimestamp) {
        List<LogEntry> result = new ArrayList<>();
        for (LogEntry entry : buffer) {
            if (entry.timestamp > sinceTimestamp) {
                result.add(entry);
            }
        }
        return result;
    }

    public static JSONArray toJsonArray(List<LogEntry> entries) {
        JSONArray arr = new JSONArray();
        if (entries != null) {
            for (LogEntry entry : entries) {
                arr.put(entry.toJson());
            }
        }
        return arr;
    }
}
