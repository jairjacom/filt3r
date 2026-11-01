package com.bridge.filt3r.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Fixed bottom bar for the docked toolbar style. Always visible; it never hides
 * and never overlaps content because the activity places it below the content area.
 *
 * Icons are added in section order. The selected section gets a round highlight.
 */
public class DockedToolbar extends LinearLayout {

    private final IntConsumer onPick;
    private final List<ImageButton> buttons = new ArrayList<>();
    private final List<View> highlights = new ArrayList<>();
    private int selected = -1;

    public DockedToolbar(Context context, IntConsumer onPick) {
        super(context);
        this.onPick = onPick;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        setPadding(dp(8), dp(6), dp(8), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.BLACK);
        bg.setCornerRadius(dp(40));
        setBackground(bg);
    }

    /** Adds an item. Its index is the order of calls. */
    public void addItem(int iconRes, CharSequence label) {
        int index = buttons.size();
        FrameLayout slot = new FrameLayout(getContext());

        View highlight = new View(getContext());
        GradientDrawable hl = new GradientDrawable();
        hl.setShape(GradientDrawable.OVAL);
        hl.setColor(Color.WHITE);
        highlight.setBackground(hl);
        highlight.setVisibility(View.INVISIBLE);
        slot.addView(highlight, new FrameLayout.LayoutParams(dp(48), dp(40), Gravity.CENTER));

        ImageButton b = new ImageButton(getContext());
        b.setImageResource(iconRes);
        b.setContentDescription(label);
        b.setBackground(null);
        b.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        b.setOnClickListener(v -> onPick.accept(index));
        slot.addView(b, new FrameLayout.LayoutParams(dp(56), dp(48), Gravity.CENTER));

        addView(slot, new LayoutParams(0, dp(56), 1f));
        buttons.add(b);
        highlights.add(highlight);
    }

    /** Marks the section at {@code index} as current. */
    public void select(int index) {
        if (selected >= 0 && selected < highlights.size()) {
            highlights.get(selected).setVisibility(View.INVISIBLE);
            buttons.get(selected).setImageTintList(ColorStateList.valueOf(Color.WHITE));
        }
        selected = index;
        if (index >= 0 && index < highlights.size()) {
            highlights.get(index).setVisibility(View.VISIBLE);
            buttons.get(index).setImageTintList(ColorStateList.valueOf(Color.BLACK));
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
