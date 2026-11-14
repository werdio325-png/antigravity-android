package dev.continuum.antigravity.log;

import java.util.regex.Pattern;

/**
 * Redacts tokens, keys, and authorization secrets from log lines and reports.
 */
public final class LogRedactor {

    private static final Pattern BEARER_PATTERN = Pattern.compile("Bearer\\s+[A-Za-z0-9_\\-\\.~]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSRF_PATTERN = Pattern.compile("csrf[_-]?token[=:\\s]+([A-Za-z0-9_\\-]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern KEY_PATTERN = Pattern.compile("(api[_-]?key|secret|password)[=:\\s]+([A-Za-z0-9_\\-]+)", Pattern.CASE_INSENSITIVE);

    private LogRedactor() {
    }

    public static String redact(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        String s = BEARER_PATTERN.matcher(line).replaceAll("Bearer [REDACTED]");
        s = CSRF_PATTERN.matcher(s).replaceAll("csrf_token=[REDACTED]");
        s = KEY_PATTERN.matcher(s).replaceAll("$1=[REDACTED]");
        return s;
    }
}
