package com.bridge.filt3r;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.View;

/**
 * The visual-effects switches. Minimal, Standard and Full are only named bundles of the same
 * individual switches; Custom keeps one switch per effect. Code asks {@link #on}, never which
 * level is chosen. To add an effect, add one line to {@link Effect}: the Settings sheet is built
 * from that list.
 */
public final class Effects {

    public static final int MINIMAL = 0;
    public static final int STANDARD = 1;
    public static final int FULL = 2;
    public static final int CUSTOM = 3;

    public enum Effect {
        // key, label, summary, motion, on in Minimal, on in Standard, on in Full
        TRANSITIONS("transitions", "Transitions",
                "Slides and fades between screens, and the connecting pulse", true, false, true, true),
        HAPTICS("haptics", "Haptics",
                "Small vibrations when you change section", false, false, true, true);

        public final String key;
        public final String label;
        public final String summary;
        /** True for effects that move; the system "remove animations" setting turns them off. */
        final boolean motion;
        private final boolean[] byLevel;

        Effect(String key, String label, String summary, boolean motion,
               boolean minimal, boolean standard, boolean full) {
            this.key = key;
            this.label = label;
            this.summary = summary;
            this.motion = motion;
            this.byLevel = new boolean[] {minimal, standard, full};
        }
    }

    private Effects() {
    }

    /** True if the effect should run now. */
    public static boolean on(Context context, Effect effect) {
        if (effect.motion && !ValueAnimator.areAnimatorsEnabled()) {
            return false;
        }
        return chosen(context, effect);
    }

    /** {@code ms} if the effect runs, otherwise 0 (the change then happens at once). */
    public static int ms(Context context, Effect effect, int ms) {
        return on(context, effect) ? ms : 0;
    }

    /** Performs the haptic only when the Haptics effect is on. */
    public static void haptic(View view, int feedbackConstant) {
        if (on(view.getContext(), Effect.HAPTICS)) {
            view.performHapticFeedback(feedbackConstant);
        }
    }

    public static int level(Context context) {
        return new Settings(context).effectsLevel();
    }

    /** Changing to Custom starts from the switches the previous level had. */
    public static void setLevel(Context context, int level) {
        Settings settings = new Settings(context);
        if (level == CUSTOM && settings.effectsLevel() != CUSTOM) {
            for (Effect e : Effect.values()) {
                settings.setEffectCustom(e.key, chosen(context, e));
            }
        }
        settings.setEffectsLevel(level);
    }

    /** The switch as the user set it, ignoring the system animation setting (for the Settings sheet). */
    public static boolean chosen(Context context, Effect effect) {
        Settings settings = new Settings(context);
        int level = settings.effectsLevel();
        if (level == CUSTOM) {
            return settings.effectCustom(effect.key, effect.byLevel[STANDARD]);
        }
        return effect.byLevel[level];
    }

    /** Sets one switch; this makes the level Custom. */
    public static void set(Context context, Effect effect, boolean on) {
        setLevel(context, CUSTOM);
        new Settings(context).setEffectCustom(effect.key, on);
    }
}
