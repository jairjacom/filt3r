package com.bridge.filt3r.ui;

/**
 * Decides whether a finished touch gesture is a horizontal section swipe.
 *
 * Returns +1 for a swipe to the next section (finger moves left), -1 for the
 * previous one (finger moves right), and 0 when the gesture is not a swipe.
 * Kept free of Android types so it can be tested on the JVM.
 */
public final class SwipeRule {

    static final float MIN_DISTANCE_PX = 80f;
    static final float MIN_VELOCITY_PX_S = 300f;

    private SwipeRule() {
    }

    public static int direction(float dx, float dy, float vx) {
        float ax = Math.abs(dx);
        // Mostly horizontal: lists scroll vertically and must not change section.
        if (ax < 2f * Math.abs(dy)) {
            return 0;
        }
        if (ax < MIN_DISTANCE_PX || Math.abs(vx) < MIN_VELOCITY_PX_S) {
            return 0;
        }
        // Fling velocity must point the same way as the drag.
        if ((dx < 0) != (vx < 0)) {
            return 0;
        }
        return dx < 0 ? 1 : -1;
    }
}
