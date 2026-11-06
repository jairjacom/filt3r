package com.bridge.filt3r.ui;

import android.content.Context;

import com.bridge.filt3r.R;
import com.google.android.material.color.MaterialColors;

/**
 * The fixed green and amber for "on" and "connecting", nudged towards the theme's main colour when
 * the user picked a generated palette or Material You, so they sit well with it. With the default
 * filt3r red they stay exactly as designed.
 */
public final class Colors {

    private Colors() {
    }

    public static int active(Context context) {
        return follow(context, context.getColor(R.color.state_active));
    }

    public static int warning(Context context) {
        return follow(context, context.getColor(R.color.state_warning));
    }

    private static int follow(Context context, int color) {
        int primary = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0);
        if (primary == context.getColor(R.color.brand_red)) {
            return color;
        }
        return MaterialColors.harmonizeWithPrimary(context, color);
    }
}
