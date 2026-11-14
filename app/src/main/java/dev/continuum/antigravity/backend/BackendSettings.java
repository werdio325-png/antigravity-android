package dev.continuum.antigravity.backend;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Which agent implementation the UI talks to.
 *
 * The embedded Continuum engine stays the default and is never modified by this
 * switch. ACP keeps its own preference namespace (workspace, session id, CLI
 * version) so the two backends can never resume each other's conversations.
 */
public final class BackendSettings {

    public enum Mode {
        /** Vendored language_server, served over localhost HTTPS (3.1 behaviour). */
        EMBEDDED,
        /** Official Antigravity CLI over ACP (`agy acp`). */
        ACP
    }

    private static final String PREFS = "continuum_backend";
    private static final String KEY_MODE = "mode";
    private static final String ACP_PREFS = "continuum_backend_acp";
    private static final String ACP_SESSION = "session_id";
    private static final String ACP_WORKSPACE = "workspace";
    private static final String ACP_MODEL = "model";

    private BackendSettings() {
    }

    public static Mode mode(Context context) {
        String value = prefs(context).getString(KEY_MODE, Mode.EMBEDDED.name());
        try {
            return Mode.valueOf(value);
        } catch (Exception e) {
            return Mode.EMBEDDED;
        }
    }

    public static void setMode(Context context, Mode mode) {
        prefs(context).edit().putString(KEY_MODE, mode.name()).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SharedPreferences acpPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(ACP_PREFS, Context.MODE_PRIVATE);
    }

    /** ACP-only state, deliberately separate from the embedded engine's data. */
    public static String acpSessionId(Context context) {
        return acpPrefs(context).getString(ACP_SESSION, null);
    }

    public static void setAcpSessionId(Context context, String sessionId) {
        acpPrefs(context).edit().putString(ACP_SESSION, sessionId).apply();
    }

    public static String acpWorkspace(Context context) {
        return acpPrefs(context).getString(ACP_WORKSPACE, null);
    }

    public static void setAcpWorkspace(Context context, String workspace) {
        acpPrefs(context).edit().putString(ACP_WORKSPACE, workspace).apply();
    }

    public static String acpModel(Context context) {
        return acpPrefs(context).getString(ACP_MODEL, null);
    }

    public static void setAcpModel(Context context, String modelId) {
        acpPrefs(context).edit().putString(ACP_MODEL, modelId).apply();
    }

    public static void clearAcpSession(Context context) {
        acpPrefs(context).edit().remove(ACP_SESSION).apply();
    }
}
