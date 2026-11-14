package dev.continuum.antigravity.ui.splash;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;

import dev.continuum.antigravity.R;

/**
 * Clean, lightweight native splash loading view.
 * Displays a smooth pulsing gray Antigravity logo with seamless entrance fade.
 */
public class NativeSplashDotsView extends View {

    private final Paint logoPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private ValueAnimator pulseAnimator;
    private ValueAnimator entranceAnimator;

    private float logoSizePx;
    private Bitmap logoBitmap;
    private float entranceAlpha = 0f;
    private float pulseAlpha = 1f;
    private boolean isDark = true;

    public NativeSplashDotsView(Context context) {
        super(context);
        init();
    }

    public NativeSplashDotsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public NativeSplashDotsView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        float density = getResources().getDisplayMetrics().density;
        logoSizePx = 56.0f * density;

        updateTint();

        try {
            logoBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.splash_logo);
        } catch (Throwable ignored) {
        }
    }

    public void setDotColor(int color) {
        // Adapt dark/light mode from theme color
        isDark = (color == android.graphics.Color.WHITE);
        updateTint();
        invalidate();
    }

    public void setDark(boolean dark) {
        isDark = dark;
        updateTint();
        invalidate();
    }

    private void updateTint() {
        // Material neutral gray: #9AA0A6 for dark mode, #5F6368 for light mode
        int gray = isDark ? 0xFF9AA0A6 : 0xFF5F6368;
        logoPaint.setColorFilter(new PorterDuffColorFilter(gray, PorterDuff.Mode.SRC_IN));
    }

    public void startAnimation() {
        stopAnimation();

        // 1. Smooth entrance fade-in
        entranceAnimator = ValueAnimator.ofFloat(0f, 1f);
        entranceAnimator.setDuration(600);
        entranceAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        entranceAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                entranceAlpha = (float) animation.getAnimatedValue();
                invalidate();
            }
        });

        // 2. Slow, gentle continuous breathing animation (2400ms per phase)
        pulseAnimator = ValueAnimator.ofFloat(0.25f, 0.90f);
        pulseAnimator.setDuration(2400);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        pulseAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                pulseAlpha = (float) animation.getAnimatedValue();
                invalidate();
            }
        });

        entranceAnimator.start();
        pulseAnimator.start();
    }

    public void stopAnimation() {
        if (entranceAnimator != null) {
            entranceAnimator.cancel();
            entranceAnimator = null;
        }
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (getVisibility() == VISIBLE) {
            startAnimation();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopAnimation();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (visibility == VISIBLE) {
            startAnimation();
        } else {
            stopAnimation();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float totalSize = logoSizePx + getPaddingLeft() + getPaddingRight();
        setMeasuredDimension(
                resolveSize((int) Math.ceil(totalSize), widthMeasureSpec),
                resolveSize((int) Math.ceil(totalSize), heightMeasureSpec)
        );
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (logoBitmap == null || logoBitmap.isRecycled()) {
            return;
        }

        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;

        float logoLeft = centerX - (logoSizePx / 2f);
        float logoTop = centerY - (logoSizePx / 2f);
        RectF destRect = new RectF(logoLeft, logoTop, logoLeft + logoSizePx, logoTop + logoSizePx);

        float currentAlpha = Math.max(0f, Math.min(1f, entranceAlpha * pulseAlpha));
        logoPaint.setAlpha(Math.round(255 * currentAlpha));

        canvas.drawBitmap(logoBitmap, null, destRect, logoPaint);
    }
}
