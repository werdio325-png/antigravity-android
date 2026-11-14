package dev.continuum.antigravity.ui.webview;

import android.content.Context;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebView;

import dev.continuum.antigravity.BuildConfig;
import dev.continuum.antigravity.bridge.AndroidBridge;
import dev.continuum.antigravity.theme.ThemeManager;

/** Creates and hardware-configures the host WebView. */
public final class WebViewFactory {

    private WebViewFactory() {
    }

    public static WebView create(Context context, AndroidBridge bridge, ThemeManager theme) {
        WebView view = new WebView(context);
        view.setBackgroundColor(theme.getBackgroundColor());
        view.setFocusable(true);
        view.setFocusableInTouchMode(true);
        view.requestFocus(View.FOCUS_DOWN);
        // Hardware layer: hand compositing to the GPU (manifest enables HW accel).
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        view.addJavascriptInterface(bridge, "Android");
        // Android WebView needs an explicit focus grant for the soft keyboard to show.
        view.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    ((WebView) v).requestFocus(View.FOCUS_DOWN);
                }
                return false;
            }
        });

        WebSettingsFactory.configure(view);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true);
        }
        return view;
    }
}
