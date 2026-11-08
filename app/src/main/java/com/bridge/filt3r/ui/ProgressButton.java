package com.bridge.filt3r.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.bridge.filt3r.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.progressindicator.CircularProgressIndicator;

/**
 * An outlined button for a real action (one that takes time): the text gives way to a spinner while
 * it runs, then to a check mark for a moment, then the text comes back. Instant switches use
 * {@link Accent#morphIcon} instead.
 */
final class ProgressButton extends FrameLayout {

    private static final long CHECK_MS = 900;

    private final MaterialButton button;
    private final CircularProgressIndicator spinner;
    private final ImageView check;
    private final ColorStateList textColors;

    ProgressButton(Context context, CharSequence text) {
        super(context);
        float density = getResources().getDisplayMetrics().density;
        button = new MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        button.setText(text);
        textColors = button.getTextColors();
        addView(button, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));

        spinner = new CircularProgressIndicator(context);
        spinner.setIndeterminate(true);
        spinner.setIndicatorSize((int) (20 * density));
        spinner.setTrackThickness((int) (3 * density));
        spinner.setVisibility(GONE);
        addView(spinner, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        check = new ImageView(context);
        check.setImageResource(R.drawable.ic_check);
        check.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorPrimary)));
        check.setVisibility(GONE);
        check.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(check, new LayoutParams((int) (20 * density), (int) (20 * density), Gravity.CENTER));
    }

    void setOnClick(OnClickListener listener) {
        button.setOnClickListener(listener);
    }

    /** The text gives way to the spinner and the button ignores taps. */
    void busy() {
        button.setEnabled(false);
        button.setTextColor(Color.TRANSPARENT);
        spinner.setVisibility(VISIBLE);
        check.setVisibility(GONE);
    }

    /** The spinner stops; on success a check shows for a moment, then the text returns. */
    void finish(boolean ok) {
        spinner.setVisibility(GONE);
        if (!ok) {
            restore();
            return;
        }
        check.setVisibility(VISIBLE);
        postDelayed(this::restore, CHECK_MS);
    }

    private void restore() {
        check.setVisibility(GONE);
        button.setTextColor(textColors);
        button.setEnabled(true);
    }
}
