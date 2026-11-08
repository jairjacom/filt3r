package com.bridge.filt3r.ui;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.ImageButton;

import com.bridge.filt3r.Effects;
import com.bridge.filt3r.R;

/** A title-bar icon button for a real action: its icon spins while the action runs, then shows a check. */
final class ActionMorph {

    private static final long CHECK_MS = 900;

    private ActionMorph() {
    }

    /** Starts the spin and returns it for {@link #finish}; with transitions off the icon only stops reacting. */
    static ObjectAnimator busy(ImageButton button) {
        button.setEnabled(false);
        ObjectAnimator spin = ObjectAnimator.ofFloat(button, View.ROTATION, 0f, 360f);
        spin.setDuration(800);
        spin.setRepeatCount(ValueAnimator.INFINITE);
        spin.setInterpolator(new LinearInterpolator());
        if (Effects.ms(button.getContext(), Effects.Effect.TRANSITIONS, 1) > 0) {
            spin.start();
        }
        return spin;
    }

    /** Stops the spin; on success the check shows for a moment before {@code normalIcon} returns. */
    static void finish(ImageButton button, ObjectAnimator spin, boolean ok, int normalIcon) {
        if (spin != null) {
            spin.cancel();
        }
        button.setRotation(0f);
        button.setEnabled(true);
        if (!ok) {
            return;
        }
        button.setImageResource(R.drawable.ic_check);
        button.postDelayed(() -> button.setImageResource(normalIcon), CHECK_MS);
    }
}
