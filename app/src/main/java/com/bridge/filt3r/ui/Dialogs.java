package com.bridge.filt3r.ui;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.view.View;
import android.view.Window;

import androidx.appcompat.app.AlertDialog;

import com.bridge.filt3r.Effects;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Dialogs and sheets. With the Blur effect on, the screen behind a dialog is blurred while it is open.
 * This blurs our own window, so it works whether or not the system allows blur across windows.
 */
public final class Dialogs {

    private static final int BLUR_DP = 10;

    /** How many dialogs are open over a screen, and the blur animation running on it. */
    private static final class Open {
        int count;
        ValueAnimator animator;
        float radius;
    }

    private static final Map<View, Open> OPEN = new WeakHashMap<>();

    private Dialogs() {
    }

    /** A Material dialog builder whose {@code show()} blurs the screen behind the dialog. */
    public static MaterialAlertDialogBuilder builder(Context context) {
        return new MaterialAlertDialogBuilder(context) {
            @Override
            public AlertDialog show() {
                AlertDialog dialog = create();
                blur(dialog);
                dialog.show();
                return dialog;
            }
        };
    }

    /** Asks before something that cannot be undone; {@code onYes} runs only on the action button. */
    public static void confirm(Context context, String heading, String message, String action, Runnable onYes) {
        builder(context)
                .setTitle(heading)
                .setMessage(message)
                .setNegativeButton("Cancel", null)
                .setPositiveButton(action, (d, w) -> onYes.run())
                .show();
    }

    /** Call before {@code show()}; the screen is blurred for as long as the dialog is on screen. */
    public static void blur(Dialog dialog) {
        Context context = dialog.getContext();
        Window window = dialog.getWindow();
        Activity activity = activityOf(context);
        if (window == null || activity == null || !Effects.on(context, Effects.Effect.BLUR)) {
            return;
        }
        View target = activity.findViewById(android.R.id.content);
        if (target == null) {
            return;
        }
        Runnable[] release = new Runnable[1];
        window.getDecorView().addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                release[0] = blurView(target);
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                if (release[0] != null) {
                    release[0].run();
                    release[0] = null;
                }
            }
        });
    }

    /**
     * Blurs a view (with the Blur effect on) until the returned action runs. Used for things that
     * are not dialogs, such as the node details. Several callers can blur the same view.
     */
    public static Runnable blurView(View target) {
        Context context = target.getContext();
        if (!Effects.on(context, Effects.Effect.BLUR)) {
            return () -> { };
        }
        float to = BLUR_DP * context.getResources().getDisplayMetrics().density;
        int ms = Effects.ms(context, Effects.Effect.TRANSITIONS, 200);
        Open open = OPEN.computeIfAbsent(target, t -> new Open());
        open.count++;
        animate(target, open, to, ms);
        return () -> {
            if (--open.count <= 0) {
                animate(target, open, 0f, ms);
            }
        };
    }

    private static void animate(View target, Open open, float to, int ms) {
        if (open.animator != null) {
            open.animator.cancel();
        }
        if (ms == 0) {
            open.radius = to;
            apply(target, to);
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(open.radius, to).setDuration(ms);
        a.addUpdateListener(u -> {
            open.radius = (float) u.getAnimatedValue();
            apply(target, open.radius);
        });
        open.animator = a;
        a.start();
    }

    private static void apply(View target, float radius) {
        target.setRenderEffect(radius < 0.5f ? null
                : RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
    }

    private static Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }
}
