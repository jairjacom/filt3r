package com.bridge.filt3r.ui;

import android.animation.ArgbEvaluator;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.HapticFeedbackConstants;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bridge.filt3r.Effects;
import com.bridge.filt3r.R;

/**
 * The big status circle: green when active, pale grey when off, amber and pulsing while connecting.
 * Colour changes fade, and the circle bounces once when it becomes active. With the Ambient glow
 * effect on, a soft glow breathes around it while connected.
 */
public class HeroView extends FrameLayout {

    public enum Mode { OFF, CONNECTING, ACTIVE, FAILED }

    private static final int SIZE_DP = 220;
    private static final int GLOW_DP = 260;
    private static final int COLOR_MS = 350;

    private final GradientDrawable circle = new GradientDrawable();
    private final GradientDrawable glowShape = new GradientDrawable();
    private final View glow;
    private final ImageView shield;
    private final TextView word;
    private ObjectAnimator pulse;
    private ObjectAnimator breathe;
    private ValueAnimator colorFade;
    private Mode mode;
    private int color;

    public HeroView(Context context) {
        super(context);
        float d = getResources().getDisplayMetrics().density;
        setClipChildren(false);
        circle.setShape(GradientDrawable.OVAL);

        glowShape.setShape(GradientDrawable.OVAL);
        glowShape.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        glowShape.setGradientRadius(GLOW_DP * d / 2f);
        glow = new View(context);
        glow.setBackground(glowShape);
        glow.setAlpha(0f);
        LayoutParams glowParams = new LayoutParams((int) (GLOW_DP * d), (int) (GLOW_DP * d), Gravity.CENTER);
        int overflow = (int) ((GLOW_DP - SIZE_DP) * d / 2f);
        glowParams.setMargins(-overflow, -overflow, -overflow, -overflow); // the hero stays 220 dp
        addView(glow, glowParams);

        LinearLayout inner = new LinearLayout(context);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setGravity(Gravity.CENTER);
        inner.setBackground(circle);

        shield = new ImageView(context);
        shield.setImageResource(R.drawable.ic_shield);
        inner.addView(shield, new LinearLayout.LayoutParams((int) (110 * d), (int) (110 * d)));

        word = new TextView(context);
        word.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        word.setGravity(Gravity.CENTER);
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
                target = getContext().getColor(R.color.state_active);
                text = "Connected";
                break;
            case CONNECTING:
                target = getContext().getColor(R.color.state_warning);
                text = "Connecting";
                break;
            case FAILED:
                target = getContext().getColor(R.color.brand_red);
                text = "Failed";
                break;
            default:
                target = getContext().getColor(R.color.state_inactive);
                text = "Off";
                break;
        }
        word.setText(text);
        fadeColor(target);
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
        circle.setColor((c & 0x00FFFFFF) | 0x33000000);
        circle.setStroke((int) (4 * getResources().getDisplayMetrics().density), c);
        glowShape.setColors(new int[] {(c & 0x00FFFFFF) | 0x66000000, c & 0x00FFFFFF});
        shield.setImageTintList(ColorStateList.valueOf(c));
        word.setTextColor(c);
    }

    /** One springy bump when the tunnel comes up. */
    private void bounce() {
        ObjectAnimator a = ObjectAnimator.ofPropertyValuesHolder(this,
                PropertyValuesHolder.ofFloat(SCALE_X, 0.92f, 1f),
                PropertyValuesHolder.ofFloat(SCALE_Y, 0.92f, 1f));
        a.setAutoCancel(true);
        a.setDuration(450);
        a.setInterpolator(new OvershootInterpolator());
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

    @Override
    protected void onDetachedFromWindow() {
        setPulsing(false);
        setBreathing(false);
        if (colorFade != null) {
            colorFade.cancel();
            colorFade = null;
        }
        super.onDetachedFromWindow();
    }
}
