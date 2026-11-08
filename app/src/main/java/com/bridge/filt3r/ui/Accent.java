package com.bridge.filt3r.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;

import com.bridge.filt3r.Effects;
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

    /**
     * Gives a pill (see {@link #selectable}) an icon that grows in when it becomes the selected one
     * and shrinks away when it is not. Call it after {@code selectable}. A pill is an instant switch,
     * so it morphs and shows no spinner.
     */
    public static void morphIcon(MaterialButton button, int iconRes) {
        Context context = button.getContext();
        int size = (int) (18 * context.getResources().getDisplayMetrics().density + 0.5f);
        button.setIconTint(button.getTextColors());
        button.setIconSize(0);
        button.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        button.addOnCheckedChangeListener((b, checked) -> {
            int ms = Effects.ms(context, Effects.Effect.TRANSITIONS, 180);
            if (checked) {
                b.setIcon(context.getDrawable(iconRes));
            }
            if (ms == 0) {
                b.setIconSize(checked ? size : 0);
                if (!checked) {
                    b.setIcon(null);
                }
                return;
            }
            ValueAnimator grow = checked ? ValueAnimator.ofInt(0, size) : ValueAnimator.ofInt(size, 0);
            grow.setDuration(ms);
            grow.addUpdateListener(a -> b.setIconSize((Integer) a.getAnimatedValue()));
            if (!checked) {
                grow.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (!b.isChecked()) {
                            b.setIcon(null);
                        }
                    }
                });
            }
            grow.start();
        });
    }
}
