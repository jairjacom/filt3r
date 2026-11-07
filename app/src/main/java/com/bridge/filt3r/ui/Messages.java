package com.bridge.filt3r.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bridge.filt3r.Effects;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.snackbar.Snackbar;

/**
 * Short messages for the user: a small rounded bar at the bottom that fades away by itself.
 * Material's own Snackbar cannot be used here: it inflates a plain Button, and this app's
 * activity is not an AppCompatActivity, so that inflation crashes.
 * {@code length} is Snackbar.LENGTH_SHORT or LENGTH_LONG (only the two constants are used).
 */
public final class Messages {

    private static final int SHORT_MS = 2200;
    private static final int LONG_MS = 3800;
    private static final int FADE_MS = 200;

    private Messages() {
    }

    public static void show(View anchor, CharSequence text) {
        show(anchor, text, Snackbar.LENGTH_LONG);
    }

    /** Shown above the bottom toolbar of the main screen. */
    public static void show(View anchor, CharSequence text, int length) {
        post(anchor, text, length, 88, null, null);
    }

    /** Like {@link #show(View, CharSequence, int)} with one button, for example Undo; stays up the longer time. */
    public static void show(View anchor, CharSequence text, String actionLabel, Runnable action) {
        post(anchor, text, Snackbar.LENGTH_LONG, 88, actionLabel, action);
    }

    /** For dialogs and bottom sheets: shown in the anchor's own window, close to the bottom edge. */
    public static void showHere(View anchor, CharSequence text, int length) {
        post(anchor, text, length, 24, null, null);
    }

    /** Like {@link #showHere(View, CharSequence, int)} with one button, for example Undo. */
    public static void showHere(View anchor, CharSequence text, String actionLabel, Runnable action) {
        post(anchor, text, Snackbar.LENGTH_LONG, 24, actionLabel, action);
    }

    private static void post(View anchor, CharSequence text, int length, int bottomDp,
                             String actionLabel, Runnable action) {
        View root = anchor.getRootView();
        ViewGroup found = root.findViewById(android.R.id.content);
        if (found == null && root instanceof FrameLayout) {
            found = (ViewGroup) root;
        }
        if (found == null) {
            return;
        }
        final ViewGroup host = found;
        Context context = anchor.getContext();
        float density = context.getResources().getDisplayMetrics().density;

        TextView message = new TextView(context);
        message.setText(text);
        message.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        message.setTextColor(MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorOnSurfaceInverse, 0xFFFFFFFF));

        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (16 * density);
        bar.setPadding(pad, (int) (12 * density), actionLabel == null ? pad : (int) (8 * density),
                (int) (12 * density));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorSurfaceInverse, 0xFF303030));
        bg.setCornerRadius(12 * density);
        bar.setBackground(bg);
        bar.setElevation(6 * density);
        bar.setClickable(true);
        bar.setOnClickListener(v -> host.removeView(v)); // a tap dismisses it
        // Screen readers announce the message when it appears.
        bar.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        bar.addView(message, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        if (actionLabel != null && action != null) {
            TextView button = new TextView(context);
            button.setText(actionLabel);
            button.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
            button.setTextColor(MaterialColors.getColor(context,
                    com.google.android.material.R.attr.colorPrimaryInverse, 0xFFFFFFFF));
            button.setGravity(Gravity.CENTER);
            button.setMinHeight((int) (48 * density));
            button.setPadding((int) (12 * density), 0, (int) (12 * density), 0);
            button.setBackground(Ripple.borderless(context));
            button.setOnClickListener(v -> {
                host.removeView(bar);
                action.run();
            });
            bar.addView(button, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        int side = (int) (16 * density);
        lp.setMargins(side, 0, side, (int) (bottomDp * density));

        // One message at a time: a new one replaces the old.
        View old = host.findViewWithTag(Messages.class);
        if (old != null) {
            host.removeView(old);
        }
        bar.setTag(Messages.class);
        final ViewGroup target = host;
        target.addView(bar, lp);

        int fade = Effects.ms(context, Effects.Effect.TRANSITIONS, FADE_MS);
        if (fade > 0) {
            bar.setAlpha(0f);
            bar.animate().alpha(1f).setDuration(fade).start();
        }
        int shown = length == Snackbar.LENGTH_SHORT ? SHORT_MS : LONG_MS;
        bar.postDelayed(() -> {
            if (bar.getParent() == null) {
                return;
            }
            if (fade > 0) {
                bar.animate().alpha(0f).setDuration(fade).withEndAction(() -> target.removeView(bar)).start();
            } else {
                target.removeView(bar);
            }
        }, shown);
    }
}
