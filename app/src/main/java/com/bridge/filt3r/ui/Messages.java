package com.bridge.filt3r.ui;

import android.view.View;
import android.view.ViewGroup;

import com.google.android.material.snackbar.Snackbar;

/** Short messages for the user: a Snackbar lifted above the bottom toolbar. */
public final class Messages {
    private Messages() {
    }

    public static void show(View anchor, CharSequence text) {
        show(anchor, text, Snackbar.LENGTH_LONG);
    }

    /** {@code length} is Snackbar.LENGTH_SHORT, LENGTH_LONG or LENGTH_INDEFINITE. */
    public static void show(View anchor, CharSequence text, int length) {
        Snackbar s = Snackbar.make(anchor, text, length);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) s.getView().getLayoutParams();
        lp.bottomMargin += (int) (88 * anchor.getResources().getDisplayMetrics().density + 0.5f);
        s.getView().setLayoutParams(lp); // Material reads the margins again when it shows the bar
        s.show();
    }

    /** For dialogs and bottom sheets: shown in the anchor's own window, not lifted above the toolbar. */
    public static void showHere(View anchor, CharSequence text, int length) {
        Snackbar.make(anchor, text, length).show();
    }
}
