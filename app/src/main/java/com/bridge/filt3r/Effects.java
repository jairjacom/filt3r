package com.bridge.filt3r;

import android.animation.ValueAnimator;
import android.content.Context;
import android.view.HapticFeedbackConstants;
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
        // key, label, summary, motion, restart, on in Minimal, on in Standard, on in Full
        // restart: the look is fixed when the screen is built, so changing it restarts the screen.
        TRANSITIONS("transitions", "Transitions",
                "Slides and fades between screens, and the connecting pulse", true, false, false, true, true),
        HAPTICS("haptics", "Haptics",
                "Small vibrations when you change section, flip a switch or add a rule", false, false, false, true, true),
        AMBIENT("ambient", "Ambient glow",
                "A slow glow around the status circle while connected", true, false, false, false, true),
        SPARKLINE("sparkline", "Traffic graph",
                "A small graph of recent traffic in the Traffic card", false, false, false, true, true),
        SHADER("shader", "Animated background",
                "Two soft blobs of colour drifting behind the Status screen", true, false, false, false, true),
        NODE_BARS("nodebars", "Latency bars",
                "A thin bar under each node showing how fast it is", false, false, false, true, true),
        TICKERS("tickers", "Number tickers",
                "Counters count up smoothly instead of jumping", true, false, false, true, true),
        SPRING("spring", "Springy motion",
                "Bounces and settles like a spring instead of following a fixed curve", true, false, false, true, true),
        SHAPE_MORPH("morph", "Shape-shifting circle",
                "The status circle changes shape between Off, Connecting, Connected and Failed", true, false, false, false, true),
        EXPRESSIVE_BUTTONS("buttons", "Expressive buttons",
                "Rounder buttons that change shape when pressed. Off gives the classic buttons", false, true, false, true, true),
        BOLD_TYPE("boldtype", "Bold headings",
                "Heavier type for the status word and section titles", false, true, false, true, true),
        BLUR("blur", "Blurred dialogs",
                "Frosted blur behind dialogs and sheets, when the system's window blur is on", false, false, false, false, true);

        public final String key;
        public final String label;
        public final String summary;
        /** True for effects that move; the system "remove animations" setting turns them off. */
        final boolean motion;
        /** True if the look is fixed when the screen is built, so a change needs the screen to restart. */
        public final boolean restart;
        private final boolean[] byLevel;

        Effect(String key, String label, String summary, boolean motion, boolean restart,
               boolean minimal, boolean standard, boolean full) {
            this.key = key;
            this.label = label;
            this.summary = summary;
            this.motion = motion;
            this.restart = restart;
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

    /** One haptic of the given {@link android.view.HapticFeedbackConstants} kind, when the Haptics effect is on. */
    public static void haptic(View view, int feedbackConstant) {
        if (on(view.getContext(), Effect.HAPTICS)) {
            view.performHapticFeedback(feedbackConstant);
        }
    }

    /** The system's switch-flip haptic: a different feel for on and off. */
    public static void toggle(View view, boolean on) {
        haptic(view, on ? HapticFeedbackConstants.TOGGLE_ON : HapticFeedbackConstants.TOGGLE_OFF);
    }

    /** Which look effects are on, as bits; if this changes, the screen has to restart to show it. */
    public static int lookBits(Context context) {
        int bits = 0;
        for (Effect e : Effect.values()) {
            if (e.restart && chosen(context, e)) {
                bits |= 1 << e.ordinal();
            }
        }
        return bits;
    }

    public static final int STYLE_SLIDE = 0;
    public static final int STYLE_FADE = 1;
    public static final int STYLE_POP = 2;

    /** How screens and panels come in: STYLE_SLIDE, STYLE_FADE or STYLE_POP. */
    public static int transitionStyle(Context context) {
        return new Settings(context).transitionStyle();
    }

    public static void setTransitionStyle(Context context, int style) {
        new Settings(context).setTransitionStyle(style);
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
