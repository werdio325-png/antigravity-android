package com.agy.util;

import android.view.ViewGroup;
import android.widget.FrameLayout;

/** Shared full-bleed layout params for the stacked WebViews. */
public final class ViewParams {

    private ViewParams() {
    }

    public static FrameLayout.LayoutParams full() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }
}
