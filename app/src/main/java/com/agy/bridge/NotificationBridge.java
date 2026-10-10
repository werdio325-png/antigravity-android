package com.agy.bridge;

import android.content.Context;
import com.agy.shell.ShizukuBridge;

/** Notification bridge exposed to the Web UI / Agent runtime. */
public final class NotificationBridge {

    private final Context context;
    private final ShizukuBridge shizuku;

    public NotificationBridge(Context context, ShizukuBridge shizuku) {
        this.context = context.getApplicationContext();
        this.shizuku = shizuku;
    }

    public boolean post(String title, String message, String tag) {
        if (title == null) title = "Antigravity";
        if (message == null) message = "";
        String cleanTag = tag != null ? tag : "agy_" + System.currentTimeMillis();
        
        // Use elevated rish if available
        if (shizuku != null && shizuku.isAvailable()) {
            String safeTitle = title.replace("'", "'\\''");
            String safeMessage = message.replace("'", "'\\''");
            String safeTag = cleanTag.replace("'", "'\\''");
            String cmd = "cmd notification post -S bigtext -t '" + safeTitle + "' '" + safeTag + "' '" + safeMessage + "'";
            shizuku.exec(cmd);
            return true;
        }

        return false;
    }

    public String list() {
        if (shizuku != null && shizuku.isAvailable()) {
            return shizuku.exec("dumpsys notification --noredact | grep -E 'pkg=|android.title=|android.text=' | head -n 40");
        }
        return "[]";
    }
}
