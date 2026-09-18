package com.antigravity.overlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public class OverlayService extends Service {

    public static final String ACTION_STOP = "com.antigravity.overlay.ACTION_STOP";
    public static final String ACTION_EXPAND = "com.antigravity.overlay.EXPAND";
    public static final String ACTION_COLLAPSE = "com.antigravity.overlay.COLLAPSE";
    public static final String ACTION_TOGGLE = "com.antigravity.overlay.TOGGLE";
    public static final String ACTION_PROMPT_SUBMITTED = "com.antigravity.overlay.PROMPT_SUBMITTED";
    private static final String CHANNEL_ID = "overlay_service_channel";

    private WindowManager windowManager;
    private View panelView;
    private View bubbleView;
    private WebView webView;
    private WindowManager.LayoutParams panelParams;
    private WindowManager.LayoutParams bubbleParams;

    private boolean isPanelVisible = true;
    private boolean isKeyboardFocus = false;
    private Handler mainHandler;

    private final BroadcastReceiver commandReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            if (ACTION_EXPAND.equals(action)) {
                switchToPanel();
            } else if (ACTION_COLLAPSE.equals(action)) {
                switchToBubble();
            } else if (ACTION_TOGGLE.equals(action)) {
                if (isPanelVisible) switchToBubble();
                else switchToPanel();
            } else if (ACTION_STOP.equals(action)) {
                stopSelf();
            }
        }
    };

    public class WebAppInterface {
        @JavascriptInterface
        public void onSubmit(final String text, final String model) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    handleSubmit(text, model);
                }
            });
        }

        @JavascriptInterface
        public void onContentHeightChanged(final int heightInDp) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    updatePanelHeight(heightInDp);
                }
            });
        }

        @JavascriptInterface
        public void onRequestFocus() {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    enableKeyboardFocus();
                }
            });
        }

        @JavascriptInterface
        public void onReleaseFocus() {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    disableKeyboardFocus();
                }
            });
        }

        @JavascriptInterface
        public void minimize() {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    switchToBubble();
                }
            });
        }

        @JavascriptInterface
        public void toast(final String message) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(OverlayService.this, message, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void onDragDelta(final float dx, final float dy) {
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (panelParams != null && panelView != null) {
                        panelParams.x += (int) dx;
                        panelParams.y += (int) dy;
                        if (panelParams.y < dpToPx(10)) panelParams.y = dpToPx(10);
                        try {
                            windowManager.updateViewLayout(panelView, panelParams);
                        } catch (Exception ignored) {}
                    }
                }
            });
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        try {
            createNotificationChannel();
            startForeground(1001, buildNotification());
        } catch (Throwable ignored) {}

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        initViews();

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_EXPAND);
        filter.addAction(ACTION_COLLAPSE);
        filter.addAction(ACTION_TOGGLE);
        filter.addAction(ACTION_STOP);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(commandReceiver, filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                stopSelf();
                return START_NOT_STICKY;
            } else if (ACTION_EXPAND.equals(action)) {
                switchToPanel();
            } else if (ACTION_COLLAPSE.equals(action)) {
                switchToBubble();
            }
        }
        return START_STICKY;
    }

    private void initViews() {
        LayoutInflater inflater = LayoutInflater.from(this);

        DisplayMetrics dm = getResources().getDisplayMetrics();
        int screenWidth = dm.widthPixels;
        int targetWidth = Math.min(dpToPx(760), screenWidth - dpToPx(24));

        int overlayType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        panelView = inflater.inflate(R.layout.overlay_panel, null);
        webView = panelView.findViewById(R.id.web_view);

        setupWebView();

        panelParams = new WindowManager.LayoutParams(
                targetWidth,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        panelParams.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        panelParams.x = 0;
        panelParams.y = dpToPx(60);

        bubbleView = inflater.inflate(R.layout.overlay_bubble, null);
        bubbleParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        bubbleParams.gravity = Gravity.TOP | Gravity.START;
        bubbleParams.x = dpToPx(16);
        bubbleParams.y = dpToPx(140);

        setupPanelEvents();
        setupBubbleEvents();

        windowManager.addView(panelView, panelParams);
        isPanelVisible = true;
    }

    private void setupWebView() {
        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");
        webView.loadUrl("file:///android_asset/panel.html");
    }

    private void setupPanelEvents() {
        View dragHandle = panelView.findViewById(R.id.drag_handle);
        dragHandle.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = panelParams.x;
                        initialY = panelParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        panelParams.x = initialX + (int) (event.getRawX() - initialTouchX);
                        panelParams.y = initialY + (int) (event.getRawY() - initialTouchY);
                        if (panelParams.y < dpToPx(10)) panelParams.y = dpToPx(10);
                        try {
                            windowManager.updateViewLayout(panelView, panelParams);
                        } catch (Exception ignored) {}
                        return true;
                }
                return false;
            }
        });
    }

    private void setupBubbleEvents() {
        bubbleView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;
            private long touchStartTime;
            private boolean isDragging;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = bubbleParams.x;
                        initialY = bubbleParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        touchStartTime = System.currentTimeMillis();
                        isDragging = false;
                        return true;

                    case MotionEvent.ACTION_MOVE:
                        float diffX = Math.abs(event.getRawX() - initialTouchX);
                        float diffY = Math.abs(event.getRawY() - initialTouchY);
                        if (diffX > 15 || diffY > 15) {
                            isDragging = true;
                        }
                        if (isDragging) {
                            bubbleParams.x = initialX + (int) (event.getRawX() - initialTouchX);
                            bubbleParams.y = initialY + (int) (event.getRawY() - initialTouchY);
                            try {
                                windowManager.updateViewLayout(bubbleView, bubbleParams);
                            } catch (Exception ignored) {}
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        long duration = System.currentTimeMillis() - touchStartTime;
                        if (!isDragging && duration < 600) {
                            switchToPanel();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void updatePanelHeight(int heightInDp) {
        if (webView != null && isPanelVisible) {
            int newPx = dpToPx(heightInDp) + dpToPx(24);
            ViewGroup.LayoutParams lp = webView.getLayoutParams();
            if (lp != null && Math.abs(lp.height - newPx) > 5) {
                lp.height = newPx;
                webView.setLayoutParams(lp);
            }
        }
    }

    public void enableKeyboardFocus() {
        if (!isKeyboardFocus && isPanelVisible) {
            isKeyboardFocus = true;
            panelParams.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            try {
                windowManager.updateViewLayout(panelView, panelParams);
            } catch (Exception ignored) {}
            if (webView != null) {
                webView.requestFocus();
            }
        }
    }

    public void disableKeyboardFocus() {
        if (isKeyboardFocus && isPanelVisible) {
            isKeyboardFocus = false;
            panelParams.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null && panelView != null) {
                imm.hideSoftInputFromWindow(panelView.getWindowToken(), 0);
            }
            try {
                windowManager.updateViewLayout(panelView, panelParams);
            } catch (Exception ignored) {}
        }
    }

    public void switchToBubble() {
        if (isPanelVisible) {
            disableKeyboardFocus();
            try {
                windowManager.removeView(panelView);
            } catch (Exception ignored) {}
            isPanelVisible = false;
            try {
                windowManager.addView(bubbleView, bubbleParams);
            } catch (Exception ignored) {}
        }
    }

    public void switchToPanel() {
        if (!isPanelVisible) {
            try {
                windowManager.removeView(bubbleView);
            } catch (Exception ignored) {}
            isPanelVisible = true;
            try {
                windowManager.addView(panelView, panelParams);
            } catch (Exception ignored) {}
        }
    }

    private void handleSubmit(String text, String model) {
        if (text == null || text.trim().isEmpty()) return;

        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            ClipData clip = ClipData.newPlainText("DevPrompt", text);
            clipboard.setPrimaryClip(clip);
        }

        Intent broadcast = new Intent(ACTION_PROMPT_SUBMITTED);
        broadcast.putExtra("prompt", text);
        broadcast.putExtra("model", model);
        sendBroadcast(broadcast);

        Toast.makeText(this, "[Скопировано]: " + text, Toast.LENGTH_SHORT).show();
        disableKeyboardFocus();
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Overlay Service",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Сервис отображения панели поверх окон");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, OverlayService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(
                this, 0, stopIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(
                this, 0, openApp,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        return builder
                .setContentTitle("Dev Overlay Panel")
                .setContentText("Панель активна поверх окон")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(openPending)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Выключить", stopPending)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(commandReceiver);
        } catch (Exception ignored) {}
        try {
            if (isPanelVisible && panelView != null) {
                windowManager.removeView(panelView);
            } else if (!isPanelVisible && bubbleView != null) {
                windowManager.removeView(bubbleView);
            }
        } catch (Exception ignored) {}
    }
}
