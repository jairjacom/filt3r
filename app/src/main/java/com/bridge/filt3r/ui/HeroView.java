package com.bridge.filt3r.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.HapticFeedbackConstants;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;

import com.bridge.filt3r.Effects;
import com.bridge.filt3r.R;

/**
 * The big status circle: green when active, pale grey when off, amber and pulsing while connecting.
 * Colour changes fade, and the circle bounces once when it becomes active. With the Shape-shifting
 * circle effect on, the outline morphs between a circle, a flower, a cookie and a rounded square;
 * the shield's funnel turns into a check mark while connected. With the Ambient glow effect on, a
 * soft glow breathes around it while connected.
 */
public class HeroView extends FrameLayout {

    public enum Mode { OFF, CONNECTING, ACTIVE, FAILED }

    private static final int SIZE_DP = 220;
    private static final int GLOW_DP = 260;
    private static final int COLOR_MS = 350;
    private static final int MORPH_MS = 450;

    private final MorphDrawable circle;
    private final GradientDrawable glowShape = new GradientDrawable();
    private final View glow;
    private final ImageView shield;
    private final TextView word;
    private ObjectAnimator pulse;
    private ObjectAnimator breathe;
    private ObjectAnimator bounceLegacy;
    private SpringAnimation bounceX;
    private SpringAnimation bounceY;
    private ValueAnimator colorFade;
    private ValueAnimator shapeAnim;
    private Mode mode;
    private int color;
    private int shape = MorphDrawable.CIRCLE;

    public HeroView(Context context) {
        super(context);
        float d = getResources().getDisplayMetrics().density;
        setClipChildren(false);
        circle = new MorphDrawable(4 * d);

        glowShape.setShape(GradientDrawable.OVAL);
        glowShape.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowShape.setGradientRadius(GLOW_DP * d / 2f);
        glow = new View(context);
        glow.setBackground(glowShape);
        glow.setAlpha(0f);
        glow.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        LayoutParams glowParams = new LayoutParams((int) (GLOW_DP * d), (int) (GLOW_DP * d), Gravity.CENTER);
        int overflow = (int) ((GLOW_DP - SIZE_DP) * d / 2f);
        glowParams.setMargins(-overflow, -overflow, -overflow, -overflow); // the hero stays 220 dp
        addView(glow, glowParams);

        LinearLayout inner = new LinearLayout(context);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setGravity(Gravity.CENTER);
        inner.setBackground(circle);

        shield = new ImageView(context);
        shield.setImageResource(R.drawable.ic_shield_funnel);
        // The status word below says the same thing, so the picture is skipped by screen readers.
        shield.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        inner.addView(shield, new LinearLayout.LayoutParams((int) (110 * d), (int) (110 * d)));

        word = new TextView(context);
        word.setTextAppearance(Effects.on(context, Effects.Effect.BOLD_TYPE)
                ? com.google.android.material.R.style.TextAppearance_Material3_TitleLarge_Emphasized
                : com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        word.setGravity(Gravity.CENTER);
        // Screen readers announce each change of state (Off, Connecting, Connected, Failed).
        word.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        inner.addView(word);

        addView(inner, new LayoutParams((int) (SIZE_DP * d), (int) (SIZE_DP * d), Gravity.CENTER));
        setMode(Mode.OFF);
    }

    public void setMode(Mode newMode) {
        Mode before = mode;
        mode = newMode;
        int target;
        String text;
        switch (newMode) {
            case ACTIVE:
                target = Colors.active(getContext());
                text = getContext().getString(R.string.hero_connected);
                break;
            case CONNECTING:
                target = Colors.warning(getContext());
                text = getContext().getString(R.string.hero_connecting);
                break;
            case FAILED:
                target = getContext().getColor(R.color.brand_red);
                text = getContext().getString(R.string.hero_failed);
                break;
            default:
                target = getContext().getColor(R.color.state_inactive);
                text = getContext().getString(R.string.hero_off);
                break;
        }
        word.setText(text);
        fadeColor(target);
        morphTo(Effects.on(getContext(), Effects.Effect.SHAPE_MORPH) ? shapeFor(newMode) : MorphDrawable.CIRCLE);
        showShield(before, newMode);
        boolean transitions = Effects.on(getContext(), Effects.Effect.TRANSITIONS);
        setPulsing(newMode == Mode.CONNECTING && transitions);
        if (before != null && before != newMode) {
            if (newMode == Mode.ACTIVE) {
                Effects.haptic(this, HapticFeedbackConstants.CONFIRM);
            } else if (newMode == Mode.FAILED) {
                Effects.haptic(this, HapticFeedbackConstants.REJECT);
            }
        }
        if (newMode == Mode.ACTIVE && before != Mode.ACTIVE && before != null && transitions) {
            bounce();
        }
        setBreathing(newMode == Mode.ACTIVE && Effects.on(getContext(), Effects.Effect.AMBIENT));
    }

    private static int shapeFor(Mode m) {
        switch (m) {
            case CONNECTING:
                return MorphDrawable.FLOWER;
            case ACTIVE:
                return MorphDrawable.COOKIE;
            case FAILED:
                return MorphDrawable.SQUIRCLE;
            default:
                return MorphDrawable.CIRCLE;
        }
    }

    /** Morphs the outline to {@code target}; at once if the effect is off or the view is not on screen. */
    private void morphTo(int target) {
        if (shapeAnim != null) {
            shapeAnim.cancel(); // its end action leaves the outline at the shape it was heading for
            shapeAnim = null;
        }
        int from = shape;
        shape = target;
        int ms = Effects.ms(getContext(), Effects.Effect.SHAPE_MORPH, MORPH_MS);
        if (from == target || ms == 0 || !isAttachedToWindow()) {
            circle.set(target, target, 1f);
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(ms);
        a.setInterpolator(new DecelerateInterpolator(1.5f));
        a.addUpdateListener(v -> circle.set(from, target, v.getAnimatedFraction()));
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                circle.set(target, target, 1f);
            }
        });
        shapeAnim = a;
        a.start();
    }

    /** The shield's funnel turns into a check mark while connected, and back. */
    private void showShield(Mode before, Mode now) {
        boolean check = now == Mode.ACTIVE;
        boolean wasCheck = before == Mode.ACTIVE;
        if (before != null && check == wasCheck) {
            return;
        }
        boolean animated = before != null && isAttachedToWindow()
                && Effects.on(getContext(), Effects.Effect.TRANSITIONS);
        int res;
        if (check) {
            res = animated ? R.drawable.avd_funnel_to_check : R.drawable.ic_shield_check;
        } else {
            res = animated ? R.drawable.avd_check_to_funnel : R.drawable.ic_shield_funnel;
        }
        shield.setImageResource(res);
        Drawable drawable = shield.getDrawable();
        if (animated && drawable instanceof Animatable) {
            ((Animatable) drawable).start();
        }
    }

    private void fadeColor(int target) {
        if (colorFade != null) {
            colorFade.cancel();
            colorFade = null;
        }
        int ms = Effects.ms(getContext(), Effects.Effect.TRANSITIONS, COLOR_MS);
        if (ms == 0 || !isAttachedToWindow()) {
            paint(target);
            return;
        }
        colorFade = ValueAnimator.ofObject(new ArgbEvaluator(), color, target);
        colorFade.setDuration(ms);
        colorFade.addUpdateListener(a -> paint((int) a.getAnimatedValue()));
        colorFade.start();
    }

    private void paint(int c) {
        color = c;
        circle.setColors((c & 0x00FFFFFF) | 0x33000000, c);
        glowShape.setColors(new int[] {(c & 0x00FFFFFF) | 0x66000000, c & 0x00FFFFFF});
        shield.setImageTintList(ColorStateList.valueOf(c));
        word.setTextColor(c);
    }

    /** One springy bump when the tunnel comes up. */
    private void bounce() {
        if (Effects.on(getContext(), Effects.Effect.SPRING)) {
            bounceX = Motion.spring(this, DynamicAnimation.SCALE_X, 0.92f, 1f, true, bounceX);
            bounceY = Motion.spring(this, DynamicAnimation.SCALE_Y, 0.92f, 1f, true, bounceY);
            return;
        }
        if (bounceLegacy != null) {
            bounceLegacy.cancel();
        }
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(this,
                PropertyValuesHolder.ofFloat(SCALE_X, 0.92f, 1f),
                PropertyValuesHolder.ofFloat(SCALE_Y, 0.92f, 1f));
        a.setAutoCancel(true);
        a.setDuration(450);
        a.setInterpolator(new OvershootInterpolator());
        bounceLegacy = a;
        a.start();
    }

    private void setPulsing(boolean on) {
        if (on && pulse == null) {
            pulse = ObjectAnimator.ofPropertyValuesHolder(this,
                    PropertyValuesHolder.ofFloat(SCALE_X, 1f, 1.06f),
                    PropertyValuesHolder.ofFloat(SCALE_Y, 1f, 1.06f));
            pulse.setAutoCancel(true);
            pulse.setDuration(700);
            pulse.setRepeatMode(ObjectAnimator.REVERSE);
            pulse.setRepeatCount(ObjectAnimator.INFINITE);
            pulse.setInterpolator(new AccelerateDecelerateInterpolator());
            pulse.start();
        } else if (!on && pulse != null) {
            pulse.cancel();
            pulse = null;
            setScaleX(1f);
            setScaleY(1f);
        }
    }

    /** A slow glow that grows and fades; only while connected and only with the Ambient glow effect. */
    private void setBreathing(boolean on) {
        if (on && breathe == null) {
            breathe = ObjectAnimator.ofPropertyValuesHolder(glow,
                    PropertyValuesHolder.ofFloat(ALPHA, 0.35f, 1f),
                    PropertyValuesHolder.ofFloat(SCALE_X, 0.95f, 1.08f),
                    PropertyValuesHolder.ofFloat(SCALE_Y, 0.95f, 1.08f));
            breathe.setDuration(2600);
            breathe.setRepeatMode(ObjectAnimator.REVERSE);
            breathe.setRepeatCount(ObjectAnimator.INFINITE);
            breathe.setInterpolator(new AccelerateDecelerateInterpolator());
            breathe.start();
        } else if (!on && breathe != null) {
            breathe.cancel();
            breathe = null;
            glow.setAlpha(0f);
        }
    }

    /** Centre of the shield on screen, for the splash screen to hand over to; false until it is laid out. */
    public boolean shieldCenter(int[] out) {
        if (shield.getWidth() == 0) {
            return false;
        }
        shield.getLocationOnScreen(out);
        out[0] += shield.getWidth() / 2;
        out[1] += shield.getHeight() / 2;
        return true;
    }

    public int shieldSize() {
        return shield.getWidth();
    }

    @Override
    protected void onDetachedFromWindow() {
        setPulsing(false);
        setBreathing(false);
        if (bounceLegacy != null) {
            bounceLegacy.cancel();
            bounceLegacy = null;
        }
        Motion.stop(bounceX);
        Motion.stop(bounceY);
        if (colorFade != null) {
            colorFade.cancel();
            colorFade = null;
        }
        if (shapeAnim != null) {
            shapeAnim.cancel();
            shapeAnim = null;
        }
        super.onDetachedFromWindow();
    }
}
