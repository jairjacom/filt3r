package com.bridge.filt3r.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import com.bridge.filt3r.Effects;
import com.google.android.material.color.MaterialColors;

/**
 * Floating pill toolbar at the bottom of the screen.
 *
 * Auto-hides after a few idle seconds and leaves a thin handle at the bottom edge;
 * tapping or swiping up on the handle brings it back. With no items it stays gone.
 */
public class FloatingToolbar extends FrameLayout {

    private static final long IDLE_MS = 3000;
    private static final int ANIM_MS = 220;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final LinearLayout pill;
    private final View handle;
    private final View handleTouch;
    private boolean autoHide = true;
    private boolean hidden;
    private int items;

    private final Runnable hideTask = this::hide;

    public FloatingToolbar(Context context) {
        super(context);
        setVisibility(GONE);
        setClipChildren(false);

        pill = new LinearLayout(context);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        int padH = dp(8);
        pill.setPadding(padH, dp(6), padH, dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorSurfaceContainer));
        bg.setCornerRadius(dp(40));
        pill.setBackground(bg);
        pill.setElevation(dp(6));
        LayoutParams pillParams = new LayoutParams(LayoutParams.WRAP_CONTENT,
                LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        pillParams.bottomMargin = dp(16);
        addView(pill, pillParams);

        handle = new View(context);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurface));
        handleBg.setCornerRadius(dp(3));
        handle.setBackground(handleBg);
        handle.setAlpha(0f);
        LayoutParams handleParams = new LayoutParams(dp(48), dp(5),
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        handleParams.bottomMargin = dp(28); // clear of the system gesture bar
        addView(handle, handleParams);

        handleTouch = new View(context);
        handleTouch.setVisibility(GONE);
        addView(handleTouch, new LayoutParams(dp(240), dp(88),
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM));
        GestureDetector gestures = new GestureDetector(context,
                new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public boolean onDown(MotionEvent e) {
                        return true;
                    }

                    @Override
                    public boolean onSingleTapUp(MotionEvent e) {
                        show();
                        return true;
                    }

                    @Override
                    public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                        if (vy < 0) {
                            show();
                        }
                        return true;
                    }
                });
        handleTouch.setOnTouchListener((v, e) -> gestures.onTouchEvent(e));
    }

    /** Adds a button. Call before the first {@link #poke()}. */
    public void addItem(int iconRes, CharSequence label, Runnable action) {
        ImageButton b = new ImageButton(getContext());
        b.setImageResource(iconRes);
        b.setContentDescription(label);
        b.setBackground(null);
        b.setImageTintList(android.content.res.ColorStateList.valueOf(MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorOnSurface)));
        b.setOnClickListener(v -> {
            poke();
            action.run();
        });
        pill.addView(b, new LinearLayout.LayoutParams(dp(56), dp(48)));
        items++;
        setVisibility(VISIBLE);
    }

    /** false pins the toolbar open (the Settings "pin" switch). */
    public void setAutoHide(boolean enabled) {
        autoHide = enabled;
        if (enabled) {
            poke();
        } else {
            handler.removeCallbacks(hideTask);
            show();
        }
    }

    /** Call on any user touch: restarts the idle timer. */
    public void poke() {
        if (items == 0) {
            return;
        }
        handler.removeCallbacks(hideTask);
        if (autoHide && !hidden) {
            handler.postDelayed(hideTask, IDLE_MS);
        }
    }

    /** Stops the idle timer while the activity is in the background. */
    public void pause() {
        handler.removeCallbacks(hideTask);
    }

    public void show() {
        if (items == 0) {
            return;
        }
        hidden = false;
        handleTouch.setVisibility(GONE);
        handle.animate().alpha(0f).setDuration(Effects.ms(getContext(), Effects.Effect.TRANSITIONS, ANIM_MS)).start();
        pill.setVisibility(VISIBLE);
        pill.animate().translationY(0f).alpha(1f)
                .setDuration(Effects.ms(getContext(), Effects.Effect.TRANSITIONS, ANIM_MS)).start();
        poke();
    }

    private void hide() {
        if (!autoHide || items == 0) {
            return;
        }
        hidden = true;
        pill.animate().translationY(pill.getHeight() + dp(24)).alpha(0f)
                .setDuration(Effects.ms(getContext(), Effects.Effect.TRANSITIONS, ANIM_MS)).withEndAction(() -> {
                    if (hidden) {
                        pill.setVisibility(INVISIBLE);
                    }
                }).start();
        handle.animate().alpha(0.4f).setDuration(Effects.ms(getContext(), Effects.Effect.TRANSITIONS, ANIM_MS)).start();
        handleTouch.setVisibility(VISIBLE);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
