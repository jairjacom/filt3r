package com.bridge.filt3r.ui;

import android.view.View;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;

import com.bridge.filt3r.Effects;

/**
 * Spring motion for one view property, with the Material 3 Expressive spatial spring values.
 * Runs only while the Springy motion effect is on; otherwise {@link #spring} returns null and the
 * caller keeps its normal animation.
 */
public final class Motion {

    // Material 3 Expressive "default spatial" and "fast spatial" tokens (stiffness, damping ratio).
    private static final float DEFAULT_STIFFNESS = 380f;
    private static final float DEFAULT_DAMPING = 0.8f;
    private static final float FAST_STIFFNESS = 800f;
    private static final float FAST_DAMPING = 0.6f;

    private Motion() {
    }

    /**
     * Springs {@code property} of {@code view} from {@code from} to {@code to}. {@code previous} is the
     * spring that last drove this property (or null); it is stopped first. Returns the new spring, or
     * null if the effect is off (nothing was changed then).
     */
    public static SpringAnimation spring(View view, DynamicAnimation.ViewProperty property, float from, float to,
                                         boolean fast, SpringAnimation previous) {
        if (!Effects.on(view.getContext(), Effects.Effect.SPRING)) {
            return null;
        }
        stop(previous);
        SpringForce force = new SpringForce(to)
                .setStiffness(fast ? FAST_STIFFNESS : DEFAULT_STIFFNESS)
                .setDampingRatio(fast ? FAST_DAMPING : DEFAULT_DAMPING);
        SpringAnimation animation = new SpringAnimation(view, property);
        animation.setSpring(force);
        animation.setStartValue(from);
        animation.start();
        return animation;
    }

    /** Stops a spring from {@link #spring} if it is still running. */
    public static void stop(SpringAnimation animation) {
        if (animation != null && animation.isRunning()) {
            animation.cancel();
        }
    }
}
