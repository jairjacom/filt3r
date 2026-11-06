package com.bridge.filt3r.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import com.google.android.material.badge.BadgeDrawable;
import com.google.android.material.badge.BadgeUtils;
import androidx.dynamicanimation.animation.DynamicAnimation;
import androidx.dynamicanimation.animation.SpringAnimation;

import com.bridge.filt3r.Effects;
import com.google.android.material.color.MaterialColors;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Fixed bottom bar for the docked toolbar style. Always visible; it never hides
 * and never overlaps content because the activity places it below the content area.
 *
 * Icons are added in section order. The selected section gets a round highlight.
 * All colours come from the theme, so the bar follows light, dark and generated palettes.
 */
public class DockedToolbar extends LinearLayout {

    private static final int ANIM_MS = 200;

    private final IntConsumer onPick;
    private final List<ImageButton> buttons = new ArrayList<>();
    private final List<View> highlights = new ArrayList<>();
    private final List<FrameLayout> slots = new ArrayList<>();
    private final List<CharSequence> labels = new ArrayList<>();
    private final List<BadgeDrawable> badges = new ArrayList<>();
    private final List<ValueAnimator> tints = new ArrayList<>();
    private final List<SpringAnimation> springsX = new ArrayList<>();
    private final List<SpringAnimation> springsY = new ArrayList<>();
    private final int iconColor;
    private final int selectedIconColor;
    private final int highlightColor;
    private int selected = -1;

    public DockedToolbar(Context context, IntConsumer onPick) {
        super(context);
        this.onPick = onPick;
        iconColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0);
        selectedIconColor = MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorOnPrimaryContainer, 0);
        highlightColor = MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorPrimaryContainer, 0);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(MaterialColors.getColor(context,
                com.google.android.material.R.attr.colorSurfaceContainer, 0));
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
        hl.setColor(highlightColor);
        highlight.setBackground(hl);
        highlight.setAlpha(0f);
        highlight.setScaleX(0.6f);
        highlight.setScaleY(0.6f);
        slot.addView(highlight, new FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER));

        ImageButton b = new ImageButton(getContext());
        b.setImageResource(iconRes);
        b.setContentDescription(label);
        b.setBackground(Ripple.borderless(getContext()));
        b.setImageTintList(ColorStateList.valueOf(iconColor));
        b.setOnClickListener(v -> onPick.accept(index));
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        slot.addView(b, new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER));

        addView(slot, new LayoutParams(dp(48), dp(48)));
        slots.add(slot);
        labels.add(label);
        badges.add(null);
        buttons.add(b);
        highlights.add(highlight);
        tints.add(null);
        springsX.add(null);
        springsY.add(null);
    }

    /**
     * Shows a number on an item's icon (nothing when {@code count} is 0). {@code meaning} is what the number
     * counts, read out after the item name ("Nodes, 5 working").
     */
    public void setBadge(int index, int count, String meaning) {
        if (index < 0 || index >= badges.size()) {
            return;
        }
        if (!isLaidOut()) {
            post(() -> setBadge(index, count, meaning)); // the badge is placed on the icon, which needs its size
            return;
        }
        BadgeDrawable badge = badges.get(index);
        ImageButton button = buttons.get(index);
        if (count <= 0) {
            if (badge != null) {
                badge.setVisible(false);
            }
            button.setContentDescription(labels.get(index));
            return;
        }
        if (badge == null) {
            badge = BadgeDrawable.create(getContext());
            badge.setBackgroundColor(MaterialColors.getColor(getContext(),
                    androidx.appcompat.R.attr.colorPrimary, 0));
            badge.setBadgeTextColor(MaterialColors.getColor(getContext(),
                    com.google.android.material.R.attr.colorOnPrimary, 0));
            badge.setMaxCharacterCount(2);
            BadgeUtils.attachBadgeDrawable(badge, button, slots.get(index));
            badges.set(index, badge);
        }
        badge.setVisible(true);
        badge.setNumber(count);
        button.setContentDescription(labels.get(index) + ", " + count + " " + meaning);
    }

    /** Marks the section at {@code index} as current. */
    public void select(int index) {
        if (selected >= 0 && selected < highlights.size()) {
            mark(selected, false);
        }
        boolean changed = index != selected;
        selected = index;
        if (index >= 0 && index < highlights.size()) {
            mark(index, true);
            if (changed && index > 0 && isAttachedToWindow()
                    && Effects.on(getContext(), Effects.Effect.TRANSITIONS)) {
                flash(buttons.get(index));
            }
        }
    }

    /** Shows the touch ripple briefly, so a swipe between sections looks like a tap on the icon. */
    private void flash(ImageButton button) {
        button.setPressed(true);
        button.postDelayed(() -> button.setPressed(false), 160);
    }

    /** Shows or hides the highlight and tints the icon, animated when the Transitions effect is on. */
    private void mark(int index, boolean on) {
        int ms = Effects.ms(getContext(), Effects.Effect.TRANSITIONS, ANIM_MS);
        View highlight = highlights.get(index);
        ImageButton button = buttons.get(index);
        button.setSelected(on);
        button.setStateDescription(on ? "Current section" : null);
        float scale = on ? 1f : 0.6f;
        SpringAnimation sx = Motion.spring(highlight, DynamicAnimation.SCALE_X, highlight.getScaleX(), scale,
                true, springsX.get(index));
        if (sx != null) {
            springsX.set(index, sx);
            springsY.set(index, Motion.spring(highlight, DynamicAnimation.SCALE_Y, highlight.getScaleY(), scale,
                    true, springsY.get(index)));
            highlight.animate().alpha(on ? 1f : 0f).setDuration(ms).start();
        } else {
            highlight.animate().alpha(on ? 1f : 0f).scaleX(scale).scaleY(scale).setDuration(ms).start();
        }
        ValueAnimator running = tints.get(index);
        if (running != null) {
            running.cancel();
        }
        int from = button.getImageTintList().getDefaultColor();
        int to = on ? selectedIconColor : iconColor;
        if (ms == 0 || from == to) {
            button.setImageTintList(ColorStateList.valueOf(to));
            tints.set(index, null);
            return;
        }
        ValueAnimator tint = ValueAnimator.ofArgb(from, to);
        tint.setDuration(ms);
        tint.addUpdateListener(a -> button.setImageTintList(ColorStateList.valueOf((int) a.getAnimatedValue())));
        tints.set(index, tint);
        tint.start();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
