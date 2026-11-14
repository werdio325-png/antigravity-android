package dev.continuum.antigravity.log;

import org.json.JSONArray;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * Thread-safe history of recent command execution responses.
 */
public final class CommandHistory {

    public static final int DEFAULT_CAPACITY = 100;
    private final int capacity;
    private final LinkedList<CommandRecord> history = new LinkedList<>();

    public CommandHistory() {
        this(DEFAULT_CAPACITY);
    }

    public CommandHistory(int capacity) {
        this.capacity = Math.max(10, capacity);
    }

    public synchronized void record(CommandRecord record) {
        if (record == null) return;
        if (history.size() >= capacity) {
            history.removeFirst();
        }
        history.addLast(record);
    }

    public synchronized List<CommandRecord> getRecent(int maxCount) {
        int count = Math.min(Math.max(1, maxCount), history.size());
        int start = history.size() - count;
        List<CommandRecord> list = new ArrayList<>(count);
        for (int i = start; i < history.size(); i++) {
            list.add(history.get(i));
        }
        return list;
    }

    public synchronized CommandRecord getById(String id) {
        if (id == null) return null;
        for (CommandRecord r : history) {
            if (id.equals(r.id)) {
                return r;
            }
        }
        return null;
    }

    public static JSONArray toJsonArray(List<CommandRecord> records) {
        JSONArray arr = new JSONArray();
        if (records != null) {
            for (CommandRecord r : records) {
                arr.put(r.toJson());
            }
        }
        return arr;
    }
}
