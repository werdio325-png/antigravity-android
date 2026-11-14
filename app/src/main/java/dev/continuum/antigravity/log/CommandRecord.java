package dev.continuum.antigravity.log;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Detailed telemetry record of an executed command and its full response.
 */
public final class CommandRecord {

    public final String id;
    public final long timestamp;
    public final String cmd;
    public final int exitCode;
    public final long durationMs;
    public final String stdout;
    public final String stderr;
    public final String status;
    public final String source;

    public CommandRecord(String id, long timestamp, String cmd, int exitCode,
                         long durationMs, String stdout, String stderr, String status, String source) {
        this.id = id;
        this.timestamp = timestamp;
        this.cmd = cmd != null ? cmd : "";
        this.exitCode = exitCode;
        this.durationMs = durationMs;
        this.stdout = stdout != null ? stdout : "";
        this.stderr = stderr != null ? stderr : "";
        this.status = status != null ? status : "UNKNOWN";
        this.source = source != null ? source : "system";
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("id", id);
            obj.put("time", timestamp);
            obj.put("cmd", cmd);
            obj.put("exit_code", exitCode);
            obj.put("duration_ms", durationMs);
            obj.put("stdout", LogRedactor.redact(stdout));
            obj.put("stderr", LogRedactor.redact(stderr));
            obj.put("status", status);
            obj.put("source", source);
        } catch (JSONException ignored) {
        }
        return obj;
    }
}
