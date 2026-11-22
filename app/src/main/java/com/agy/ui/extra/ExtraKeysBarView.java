package com.agy.ui.extra;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure Android-SDK extra-keys bar for terminal and chat shortcuts.
 * Docked above the IME keyboard when visible; every child is non-focusable so
 * tapping keys never steals focus from the WebView or dismisses the keyboard.
 */
public final class ExtraKeysBarView extends LinearLayout {

    private static final String PREFS = "agy_prefs";
    private static final String KEY_EXTRA_KEYS_VISIBLE = "extra_keys_visible";

    public interface Listener {
        void onKeyPressed(String key, boolean ctrlActive);
        void onHideRequested();
    }

    private final View topDivider;
    private final LinearLayout rowContainer;
    private final LinearLayout keysContainer;
    private final LinearLayout ctrlShortcutsContainer;
    private final TextView ctrlButton;
    private final TextView hideButton;
    private final List<TextView> normalKeyButtons = new ArrayList<>();
    private final List<TextView> ctrlShortcutButtons = new ArrayList<>();

    private Listener listener;
    private boolean darkTheme = true;
    private boolean ctrlActive = false;

    public ExtraKeysBarView(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setFocusable(false);
        setFocusableInTouchMode(false);

        int padH = dp(6);
        int padV = dp(4);

        topDivider = new View(context);
        topDivider.setFocusable(false);
        topDivider.setFocusableInTouchMode(false);
        addView(topDivider, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        rowContainer = new LinearLayout(context);
        rowContainer.setOrientation(HORIZONTAL);
        rowContainer.setGravity(Gravity.CENTER_VERTICAL);
        rowContainer.setPadding(padH, padV, padH, padV);
        rowContainer.setFocusable(false);
        rowContainer.setFocusableInTouchMode(false);
        addView(rowContainer, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        HorizontalScrollView scrollView = new HorizontalScrollView(context);
        scrollView.setHorizontalScrollBarEnabled(false);
        scrollView.setFillViewport(false);
        scrollView.setFocusable(false);
        scrollView.setFocusableInTouchMode(false);
        LayoutParams scrollParams = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        rowContainer.addView(scrollView, scrollParams);

        keysContainer = new LinearLayout(context);
        keysContainer.setOrientation(HORIZONTAL);
        keysContainer.setGravity(Gravity.CENTER_VERTICAL);
        keysContainer.setFocusable(false);
        keysContainer.setFocusableInTouchMode(false);
        scrollView.addView(keysContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addKeyButton("ESC", "ESC");
        addKeyButton("TAB", "TAB");

        ctrlButton = createKeyCap(context, "CTRL");
        ctrlButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                hapticTap(v);
                setCtrlActive(!ctrlActive);
            }
        });
        keysContainer.addView(ctrlButton, keyLayoutParams());

        // Contextual Ctrl+letter shortcuts revealed while sticky CTRL is toggled on.
        ctrlShortcutsContainer = new LinearLayout(context);
        ctrlShortcutsContainer.setOrientation(HORIZONTAL);
        ctrlShortcutsContainer.setGravity(Gravity.CENTER_VERTICAL);
        ctrlShortcutsContainer.setFocusable(false);
        ctrlShortcutsContainer.setFocusableInTouchMode(false);
        ctrlShortcutsContainer.setVisibility(GONE);
        addCtrlShortcut("^C", "c");
        addCtrlShortcut("^D", "d");
        addCtrlShortcut("^Z", "z");
        addCtrlShortcut("^L", "l");
        keysContainer.addView(ctrlShortcutsContainer, new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        addKeyButton("\u2191", "ArrowUp");
        addKeyButton("\u2193", "ArrowDown");
        addKeyButton("\u2190", "ArrowLeft");
        addKeyButton("\u2192", "ArrowRight");
        addKeyButton("/", "/");
        addKeyButton("@", "@");
        addKeyButton("|", "|");
        addKeyButton("~", "~");

        hideButton = createKeyCap(context, "\u2715");
        hideButton.setContentDescription("Hide extra keys");
        hideButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                hapticTap(v);
                setCtrlActive(false);
                if (listener != null) {
                    listener.onHideRequested();
                }
            }
        });
        LayoutParams hideParams = new LayoutParams(dp(34), dp(32));
        hideParams.leftMargin = dp(6);
        rowContainer.addView(hideButton, hideParams);

        applyTheme(true);
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public void applyTheme(boolean dark) {
        this.darkTheme = dark;
        int barBg = dark ? 0xFF161616 : 0xFFF3F4F6;
        int dividerColor = dark ? 0xFF2B2B2B : 0xFFD4D4D8;
        int keyBg = dark ? 0xFF262626 : 0xFFFFFFFF;
        int keyPressed = dark ? 0xFF3A3A3A : 0xFFE4E4E7;
        int keyBorder = dark ? 0xFF383838 : 0xFFD4D4D8;
        int keyText = dark ? 0xFFF2F2F2 : 0xFF18181B;
        int hideBg = dark ? 0xFF222222 : 0xFFE4E4E7;
        int hideText = dark ? 0xFFA1A1AA : 0xFF52525B;

        setBackgroundColor(barBg);
        topDivider.setBackgroundColor(dividerColor);

        for (TextView btn : normalKeyButtons) {
            btn.setBackground(makeKeyBackground(keyBg, keyPressed, keyBorder));
            btn.setTextColor(keyText);
        }
        int shortcutBg = dark ? 0xFF1E3A5F : 0xFFDBEAFE;
        int shortcutPressed = dark ? 0xFF2563EB : 0xFFBFDBFE;
        int shortcutBorder = dark ? 0xFF3B82F6 : 0xFF93C5FD;
        int shortcutText = dark ? 0xFFEFF6FF : 0xFF1D4ED8;
        for (TextView btn : ctrlShortcutButtons) {
            btn.setBackground(makeKeyBackground(shortcutBg, shortcutPressed, shortcutBorder));
            btn.setTextColor(shortcutText);
        }
        hideButton.setBackground(makeKeyBackground(hideBg, keyPressed, keyBorder));
        hideButton.setTextColor(hideText);

        updateCtrlButtonStyle();
    }

    public void resetStickyModifiers() {
        setCtrlActive(false);
    }

    private void setCtrlActive(boolean active) {
        this.ctrlActive = active;
        ctrlShortcutsContainer.setVisibility(active ? VISIBLE : GONE);
        updateCtrlButtonStyle();
    }

    private void updateCtrlButtonStyle() {
        if (ctrlActive) {
            ctrlButton.setBackground(makeKeyBackground(0xFF2563EB, 0xFF1D4ED8, 0xFF60A5FA));
            ctrlButton.setTextColor(Color.WHITE);
        } else {
            int keyBg = darkTheme ? 0xFF262626 : 0xFFFFFFFF;
            int keyPressed = darkTheme ? 0xFF3A3A3A : 0xFFE4E4E7;
            int keyBorder = darkTheme ? 0xFF383838 : 0xFFD4D4D8;
            int keyText = darkTheme ? 0xFFF2F2F2 : 0xFF18181B;
            ctrlButton.setBackground(makeKeyBackground(keyBg, keyPressed, keyBorder));
            ctrlButton.setTextColor(keyText);
        }
    }

    private void addKeyButton(String label, final String keyValue) {
        TextView btn = createKeyCap(getContext(), label);
        btn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                hapticTap(v);
                boolean withCtrl = ctrlActive;
                if (withCtrl) {
                    setCtrlActive(false);
                }
                if (listener != null) {
                    listener.onKeyPressed(keyValue, withCtrl);
                }
            }
        });
        normalKeyButtons.add(btn);
        keysContainer.addView(btn, keyLayoutParams());
    }

    private void addCtrlShortcut(String label, final String keyValue) {
        TextView btn = createKeyCap(getContext(), label);
        btn.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                hapticTap(v);
                setCtrlActive(false);
                if (listener != null) {
                    listener.onKeyPressed(keyValue, true);
                }
            }
        });
        ctrlShortcutButtons.add(btn);
        ctrlShortcutsContainer.addView(btn, keyLayoutParams());
    }

    private TextView createKeyCap(Context context, String label) {
        TextView tv = new TextView(context);
        tv.setText(label);
        tv.setGravity(Gravity.CENTER);
        tv.setSingleLine(true);
        tv.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        tv.setMinWidth(dp(36));
        tv.setPadding(dp(8), 0, dp(8), 0);
        tv.setClickable(true);
        tv.setFocusable(false);
        tv.setFocusableInTouchMode(false);
        return tv;
    }

    private LayoutParams keyLayoutParams() {
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(32));
        lp.rightMargin = dp(4);
        return lp;
    }

    private StateListDrawable makeKeyBackground(int normalColor, int pressedColor, int strokeColor) {
        float radius = dp(6);
        int stroke = dp(1);

        GradientDrawable normal = new GradientDrawable();
        normal.setShape(GradientDrawable.RECTANGLE);
        normal.setCornerRadius(radius);
        normal.setColor(normalColor);
        normal.setStroke(stroke, strokeColor);

        GradientDrawable pressed = new GradientDrawable();
        pressed.setShape(GradientDrawable.RECTANGLE);
        pressed.setCornerRadius(radius);
        pressed.setColor(pressedColor);
        pressed.setStroke(stroke, strokeColor);

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, pressed);
        states.addState(new int[]{}, normal);
        return states;
    }

    private void hapticTap(View v) {
        try {
            v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        } catch (Throwable ignored) {
        }
    }

    private int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics()));
    }

    public static boolean isVisiblePref(Context context) {
        if (context == null) {
            return false;
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_EXTRA_KEYS_VISIBLE, false);
    }

    public static void setVisiblePref(Context context, boolean visible) {
        if (context == null) {
            return;
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_EXTRA_KEYS_VISIBLE, visible)
                .apply();
    }
}
