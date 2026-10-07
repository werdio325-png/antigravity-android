package com.agy.ui.splash;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.view.View;
import android.webkit.WebView;
import android.widget.FrameLayout;

import com.agy.theme.ThemeManager;

/**
 * Owns the 3-dot splash WebView: shows the loading animation, transient
 * restart status, and fail-closed errors; dismisses it when the UI paints.
 */
public final class SplashController {

    public static final int MODE_LOADING = 0;
    public static final int MODE_TRANSIENT = 1;
    public static final int MODE_ERROR = 2;

    private final WebView view;
    private final ThemeManager theme;
    private final FrameLayout root;

    private SplashRetryListener retryListener;
    private boolean dismissed = false;
    private boolean fatalShown = false;
    private int mode = MODE_LOADING;

    public SplashController(Context context, ThemeManager theme, FrameLayout root) {
        this.theme = theme;
        this.root = root;
        this.view = new WebView(context);
        this.view.setBackgroundColor(Color.TRANSPARENT);
    }

    public WebView view() {
        return view;
    }

    public int mode() {
        return mode;
    }

    public boolean isFatalShown() {
        return fatalShown;
    }

    public void setRetryListener(SplashRetryListener listener) {
        this.retryListener = listener;
        SplashRetryBridge.bind(view, listener);
    }

    /** First render of the loading splash (before any show* call). */
    public void loadInitial() {
        mode = MODE_LOADING;
        SplashLoadingHtml.load(view, foreground(), background());
    }

    /** Show the animated loading splash again (startup or post-restart ready). */
    public void showLoading() {
        setVisible();
        if (mode != MODE_LOADING) {
            mode = MODE_LOADING;
            SplashLoadingHtml.load(view, foreground(), background());
        }
    }

    /** Non-fatal status (auto-restart running): overlay without a Retry button. */
    public void showTransient(String message) {
        if (fatalShown) {
            return;
        }
        setVisible();
        if (mode != MODE_TRANSIENT) {
            mode = MODE_TRANSIENT;
            SplashOverlayHtml.show(view, "Restarting Antigravity", message,
                    foreground(), background(), false);
        }
    }

    /** Fail-closed startup failure: readable message with a Retry action. */
    public void showFatalError(String message) {
        if (fatalShown) {
            return;
        }
        fatalShown = true;
        mode = MODE_ERROR;
        setVisible();
        SplashRetryBridge.bind(view, retryListener);
        SplashOverlayHtml.show(view, "Antigravity failed to start", message,
                foreground(), background(), retryListener != null);
    }

    /** Called when a fresh ready generation begins; errors can be shown again. */
    public void clearFatal() {
        fatalShown = false;
    }

    public void setVisible() {
        dismissed = false;
        view.setAlpha(1f);
        if (view.getVisibility() != View.VISIBLE) {
            view.setVisibility(View.VISIBLE);
        }
        if (root != null) {
            root.bringChildToFront(view);
        }
    }

    /** Idempotent; called by AndroidBridge.onUiReady() on the main thread. */
    public void dismiss() {
        if (dismissed) {
            return;
        }
        dismissed = true;
        view.animate()
                .alpha(0f)
                .setDuration(280)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        view.setVisibility(View.GONE);
                    }
                })
                .start();
    }

    /** Re-render the splash after a theme/configuration change. */
    public void refresh() {
        view.setBackgroundColor(Color.TRANSPARENT);
        if (mode == MODE_LOADING && !dismissed) {
            SplashLoadingHtml.load(view, foreground(), background());
        }
    }

    /** API31+: cross-fade the system splash into this overlay. */
    public void installExitAnimation(Activity activity) {
        SplashExitInstaller.install(activity, view);
    }

    private String foreground() {
        return theme.getForegroundHex();
    }

    private String background() {
        return theme.getBackgroundHex();
    }
}
