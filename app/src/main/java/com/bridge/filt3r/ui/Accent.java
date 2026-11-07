package com.bridge.filt3r.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;

/**
 * Colours for choices that can be selected (the buttons of a toggle group, filter pills): the
 * selected one is filled with the theme's main container colour, so the choice shows the accent
 * in every colour scheme instead of the neutral secondary colours.
 */
public final class Accent {

    private Accent() {
    }

    public static void selectable(MaterialButton button) {
        Context context = button.getContext();
        int container = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimaryContainer, 0);
        int onContainer = MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorOnPrimaryContainer, 0);
        int onSurface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0);
        int[][] states = {{android.R.attr.state_checked}, {}};
        button.setBackgroundTintList(new ColorStateList(states, new int[] {container, Color.TRANSPARENT}));
        button.setTextColor(new ColorStateList(states, new int[] {onContainer, onSurface}));
    }
}
