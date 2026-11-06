package com.bridge.filt3r.ui;

import android.app.Dialog;
import android.content.Context;
import android.view.Window;
import android.view.WindowManager;

import androidx.appcompat.app.AlertDialog;

import com.bridge.filt3r.Effects;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Dialogs and sheets. With the Blurred dialogs effect on, and the system's window blur available,
 * the screen behind a dialog is blurred; otherwise the normal dimming is used.
 */
public final class Dialogs {

    private static final int BLUR_DP = 20;

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

    /** Call before {@code show()}; does nothing if the effect is off or the system has window blur disabled. */
    public static void blur(Dialog dialog) {
        Context context = dialog.getContext();
        Window window = dialog.getWindow();
        if (window == null || !Effects.on(context, Effects.Effect.BLUR)) {
            return;
        }
        WindowManager windows = context.getSystemService(WindowManager.class);
        if (windows == null || !windows.isCrossWindowBlurEnabled()) {
            return;
        }
        float density = context.getResources().getDisplayMetrics().density;
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
        WindowManager.LayoutParams params = window.getAttributes();
        params.setBlurBehindRadius((int) (BLUR_DP * density));
        window.setAttributes(params);
    }
}
