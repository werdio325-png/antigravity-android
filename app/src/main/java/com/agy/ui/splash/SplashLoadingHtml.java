package com.agy.ui.splash;

import android.webkit.WebView;

/**
 * The authentic Antigravity loading dots (three 3px dots, 2px gap, vertical
 * bounce, 1.6s ease-in-out, delays 0/120/240ms). Colors are pre-resolved for
 * the current mode; no prefers-color-scheme media query (WebView would resolve
 * that from the system scheme and override an in-app choice).
 */
public final class SplashLoadingHtml {

    private SplashLoadingHtml() {
    }

    public static String getLoadingHtml(String foregroundColor, String backgroundColor) {
        return "<!DOCTYPE html>\n"
                + "<html>\n"
                + "<head>\n"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
                + "<style>\n"
                + "  :root {\n"
                + "    --bg: " + backgroundColor + ";\n"
                + "    --fg: " + foregroundColor + ";\n"
                + "  }\n"
                + "  html, body {\n"
                + "    margin: 0;\n"
                + "    padding: 0;\n"
                + "    height: 100%;\n"
                + "    width: 100%;\n"
                + "    background: var(--bg);\n"
                + "    color: var(--fg);\n"
                + "    display: flex;\n"
                + "    align-items: center;\n"
                + "    justify-content: center;\n"
                + "    overflow: hidden;\n"
                + "    -webkit-user-select: none;\n"
                + "  }\n"
                + "  @keyframes dot-bounce {\n"
                + "    0%, 100% { transform: translateY(0); }\n"
                + "    15% { transform: translateY(-2px); }\n"
                + "    30% { transform: translateY(0); }\n"
                + "  }\n"
                + "  .animate-dot-bounce {\n"
                + "    display: inline-block;\n"
                + "    width: 3px;\n"
                + "    height: 3px;\n"
                + "    border-radius: 50%;\n"
                + "    background-color: currentColor;\n"
                + "    animation: dot-bounce 1.6s ease-in-out infinite;\n"
                + "  }\n"
                + "</style>\n"
                + "</head>\n"
                + "<body>\n"
                + "  <span style=\"display:inline-flex;align-items:center;gap:2px;height:16px;padding:0 4px;opacity:0.5;\" aria-label=\"Loading\">\n"
                + "    <span class=\"animate-dot-bounce\" style=\"animation-delay:0ms;\"></span>\n"
                + "    <span class=\"animate-dot-bounce\" style=\"animation-delay:120ms;\"></span>\n"
                + "    <span class=\"animate-dot-bounce\" style=\"animation-delay:240ms;\"></span>\n"
                + "  </span>\n"
                + "</body>\n"
                + "</html>\n";
    }

    public static void load(WebView splashView, String foregroundColor, String backgroundColor) {
        if (splashView == null) {
            return;
        }
        splashView.loadDataWithBaseURL(null, getLoadingHtml(foregroundColor, backgroundColor),
                "text/html", "utf-8", null);
    }
}
