package com.agy.ui.splash;

import android.webkit.WebView;

/** Renders status/failure overlays whose Retry button calls window.SplashRetry. */
public final class SplashOverlayHtml {

    private SplashOverlayHtml() {
    }

    /** Transient status (e.g. auto-restart in progress): message, optional Retry. */
    public static void show(WebView splashView, String title, String message,
                            String foregroundColor, String backgroundColor, boolean retryable) {
        if (splashView == null) {
            return;
        }
        splashView.loadDataWithBaseURL(null,
                getOverlayHtml(title, message, foregroundColor, backgroundColor, retryable),
                "text/html", "utf-8", null);
    }

    public static String getOverlayHtml(String title, String message,
                                        String foregroundColor, String backgroundColor,
                                        boolean retryable) {
        String safeTitle = escape(title == null ? "Antigravity" : title);
        String safe = escape(message == null ? "unknown error" : message);
        String button = retryable
                ? "  <button class=\"retry\" onclick=\"agyRetry()\">Retry</button>\n"
                : "";
        return "<!DOCTYPE html>\n"
                + "<html>\n"
                + "<head>\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
                + "<style>\n"
                + "  body {\n"
                + "    margin: 0;\n"
                + "    padding: 24px;\n"
                + "    box-sizing: border-box;\n"
                + "    background: " + backgroundColor + ";\n"
                + "    color: " + foregroundColor + ";\n"
                + "    font-family: system-ui, -apple-system, sans-serif;\n"
                + "    display: flex;\n"
                + "    flex-direction: column;\n"
                + "    align-items: center;\n"
                + "    justify-content: center;\n"
                + "    height: 100vh;\n"
                + "    text-align: center;\n"
                + "  }\n"
                + "  .title { font-size: 15px; font-weight: 600; margin-bottom: 10px; }\n"
                + "  .msg {\n"
                + "    font-size: 12px;\n"
                + "    opacity: 0.75;\n"
                + "    word-break: break-word;\n"
                + "    max-width: 90vw;\n"
                + "    white-space: pre-wrap;\n"
                + "  }\n"
                + "  .retry {\n"
                + "    margin-top: 18px;\n"
                + "    padding: 9px 22px;\n"
                + "    font-size: 13px;\n"
                + "    color: " + backgroundColor + ";\n"
                + "    background: " + foregroundColor + ";\n"
                + "    border: none;\n"
                + "    border-radius: 6px;\n"
                + "    cursor: pointer;\n"
                + "  }\n"
                + "</style>\n"
                + "</head>\n"
                + "<body>\n"
                + "  <div class=\"title\">" + safeTitle + "</div>\n"
                + "  <div class=\"msg\">" + safe + "</div>\n"
                + button
                + "  <script>\n"
                + "    function agyRetry() {\n"
                + "      if (window.SplashRetry && window.SplashRetry.retry) {\n"
                + "        window.SplashRetry.retry();\n"
                + "      }\n"
                + "    }\n"
                + "  </script>\n"
                + "</body>\n"
                + "</html>\n";
    }

    private static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
