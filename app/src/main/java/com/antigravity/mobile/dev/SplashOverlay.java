package com.antigravity.mobile.dev;

import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

public final class SplashOverlay {

    private SplashOverlay() {}

    public static View create(Context context, String bgColor, boolean isDark) {
        WebView splashView = new WebView(context);
        splashView.setBackgroundColor(Color.parseColor(bgColor));

        String fg = isDark ? "#ffffff" : "#101010";
        String splashHtml = "<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1.0'><style>"
            + "html,body{margin:0;padding:0;height:100%;width:100%;display:flex;align-items:center;justify-content:center;overflow:hidden;"
            + "background:" + bgColor + ";color:" + fg + ";}"
            + "@keyframes dot-bounce{0%,100%{transform:translateY(0)}15%{transform:translateY(-2px)}30%{transform:translateY(0)}}"
            + ".animate-dot-bounce{animation:dot-bounce 1.6s ease-in-out infinite;display:inline-block;width:3px;height:3px;border-radius:50%;background-color:currentColor;}"
            + "</style></head><body>"
            + "<span style='display:inline-flex;align-items:center;gap:2px;height:16px;padding:0 4px;opacity:0.5;' aria-label='Loading'>"
            + "<span class='animate-dot-bounce' style='animation-delay:0ms;'></span>"
            + "<span class='animate-dot-bounce' style='animation-delay:120ms;'></span>"
            + "<span class='animate-dot-bounce' style='animation-delay:240ms;'></span>"
            + "</span></body></html>";
        splashView.loadDataWithBaseURL(null, splashHtml, "text/html", "utf-8", null);
        return splashView;
    }

    public static void dismissWithFade(View splashView, ViewGroup rootLayout, Runnable onEndAction) {
        if (splashView == null) return;
        splashView.animate()
            .alpha(0f)
            .setDuration(200)
            .withEndAction(() -> {
                if (rootLayout != null && splashView != null) {
                    rootLayout.removeView(splashView);
                    if (splashView instanceof WebView) {
                        ((WebView) splashView).destroy();
                    }
                }
                if (onEndAction != null) {
                    onEndAction.run();
                }
            })
            .start();
    }
}
