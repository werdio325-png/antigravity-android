package com.antigravity.mobile.dev;

import android.animation.Keyframe;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

public final class SplashOverlay {

    private SplashOverlay() {}

    public static View create(Context context, String bgColor, boolean isDark) {
        FrameLayout splash = new FrameLayout(context);
        splash.setBackgroundColor(Color.parseColor(bgColor));
        splash.setClickable(true);
        splash.setFocusable(true);
        splash.setClipChildren(false);
        splash.setClipToPadding(false);

        LinearLayout dotsContainer = new LinearLayout(context);
        dotsContainer.setOrientation(LinearLayout.HORIZONTAL);
        dotsContainer.setGravity(Gravity.CENTER);
        dotsContainer.setClipChildren(false);
        dotsContainer.setClipToPadding(false);

        // Matches core web_ui opacity-50 and colors
        int dotColor = isDark ? Color.argb(128, 255, 255, 255) : Color.argb(128, 16, 16, 16);
        float density = context.getResources().getDisplayMetrics().density;
        // Exact 3px dots with 2px gap (1px margin per side) from web_ui
        int dotSize = Math.max(1, Math.round(3 * density));
        int dotMargin = Math.max(1, Math.round(1 * density));
        int padV = Math.max(1, Math.round(8 * density));
        dotsContainer.setPadding(0, padV, 0, padV);

        final View[] dots = new View[3];
        AccelerateDecelerateInterpolator easeInOut = new AccelerateDecelerateInterpolator();

        for (int i = 0; i < 3; i++) {
            dots[i] = new View(context);
            GradientDrawable circle = new GradientDrawable();
            circle.setShape(GradientDrawable.OVAL);
            circle.setColor(dotColor);
            dots[i].setBackground(circle);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dotSize, dotSize);
            lp.setMargins(dotMargin, 0, dotMargin, 0);
            dotsContainer.addView(dots[i], lp);

            final int idx = i;
            // Original web_ui @keyframes dot-bounce:
            // 0%, 100%: 0
            // 15%: -2px (subtle bounce)
            // 30%: 0
            // 1.6s duration, 120ms stagger delay
            Keyframe k0 = Keyframe.ofFloat(0.00f, 0f);
            Keyframe k1 = Keyframe.ofFloat(0.15f, -2f * density);
            k1.setInterpolator(easeInOut);
            Keyframe k2 = Keyframe.ofFloat(0.30f, 0f);
            k2.setInterpolator(easeInOut);
            Keyframe k3 = Keyframe.ofFloat(1.00f, 0f);

            PropertyValuesHolder pvh = PropertyValuesHolder.ofKeyframe("translationY", k0, k1, k2, k3);
            ValueAnimator anim = ValueAnimator.ofPropertyValuesHolder(pvh);
            anim.setDuration(1600);
            anim.setRepeatCount(ValueAnimator.INFINITE);
            anim.setStartDelay(idx * 120);
            anim.addUpdateListener(animation -> {
                if (dots[idx] != null) {
                    dots[idx].setTranslationY((float) animation.getAnimatedValue("translationY"));
                }
            });
            anim.start();
        }

        FrameLayout.LayoutParams centerLp = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
        splash.addView(dotsContainer, centerLp);

        return splash;
    }

    public static void dismissWithFade(View splashView, ViewGroup rootLayout, Runnable onEndAction) {
        if (splashView == null) return;
        splashView.animate()
            .alpha(0f)
            .setDuration(220)
            .withEndAction(() -> {
                if (splashView != null) {
                    splashView.setVisibility(View.GONE);
                }
                if (rootLayout != null && splashView != null) {
                    rootLayout.removeView(splashView);
                }
                if (onEndAction != null) {
                    onEndAction.run();
                }
            })
            .start();
    }
}
