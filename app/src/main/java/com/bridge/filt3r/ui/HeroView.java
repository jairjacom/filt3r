package com.bridge.filt3r.ui;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bridge.filt3r.Effects;
import com.bridge.filt3r.R;

/** The big status circle: green when active, pale grey when off, amber and pulsing while connecting. */
public class HeroView extends FrameLayout {

    public enum Mode { OFF, CONNECTING, ACTIVE, FAILED }

    private static final int SIZE_DP = 220;

    private final GradientDrawable circle = new GradientDrawable();
    private final ImageView shield;
    private final TextView word;
    private ObjectAnimator pulse;

    public HeroView(Context context) {
        super(context);
        float d = getResources().getDisplayMetrics().density;
        circle.setShape(GradientDrawable.OVAL);

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

        addView(inner, new LayoutParams((int) (SIZE_DP * d), (int) (SIZE_DP * d)));
        setMode(Mode.OFF);
    }

    public void setMode(Mode mode) {
        int color;
        String text;
        switch (mode) {
            case ACTIVE:
                color = getContext().getColor(R.color.state_active);
                text = "Connected";
                break;
            case CONNECTING:
                color = getContext().getColor(R.color.state_warning);
                text = "Connecting";
                break;
            case FAILED:
                color = getContext().getColor(R.color.brand_red);
                text = "Failed";
                break;
            default:
                color = getContext().getColor(R.color.state_inactive);
                text = "Off";
                break;
        }
        circle.setColor((color & 0x00FFFFFF) | 0x33000000);
        circle.setStroke((int) (4 * getResources().getDisplayMetrics().density), color);
        shield.setImageTintList(ColorStateList.valueOf(color));
        word.setText(text);
        word.setTextColor(color);
        setPulsing(mode == Mode.CONNECTING && Effects.on(getContext(), Effects.Effect.TRANSITIONS));
    }

    private void setPulsing(boolean on) {
        if (on && pulse == null) {
            pulse = ObjectAnimator.ofPropertyValuesHolder(this,
                    android.animation.PropertyValuesHolder.ofFloat(SCALE_X, 1f, 1.06f),
                    android.animation.PropertyValuesHolder.ofFloat(SCALE_Y, 1f, 1.06f));
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

    @Override
    protected void onDetachedFromWindow() {
        setPulsing(false);
        super.onDetachedFromWindow();
    }
}
