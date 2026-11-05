package com.bridge.filt3r.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;

/** Theme ripple for icon-only buttons. */
final class Ripple {
    private Ripple() {
    }

    /** The theme's borderless selectable background (a round ripple). */
    static Drawable borderless(Context c) {
        TypedArray a = c.obtainStyledAttributes(new int[] {android.R.attr.selectableItemBackgroundBorderless});
        try {
            return a.getDrawable(0);
        } finally {
            a.recycle();
        }
    }
}
