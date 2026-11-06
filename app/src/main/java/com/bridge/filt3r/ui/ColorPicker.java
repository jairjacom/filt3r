package com.bridge.filt3r.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.HapticFeedbackConstants;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bridge.filt3r.Effects;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.slider.Slider;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.Locale;
import java.util.function.IntConsumer;

/** The "Select colour" dialog: a Standard tab of named swatches and a Custom tab (hex and sliders). */
final class ColorPicker {

    private static final int[] SWATCHES = {
        0xFFE5383B, 0xFFDC143C, 0xFFFB8C00, 0xFFFFB300, 0xFFFDD835, 0xFF9CCC65, 0xFF43A047, 0xFF00897B,
        0xFF00ACC1, 0xFF29B6F6, 0xFF1E88E5, 0xFF3949AB, 0xFF8E24AA, 0xFFEC407A, 0xFF8D6E63, 0xFF607D8B,
    };
    private static final String[] NAMES = {
        "filt3r red", "Crimson", "Orange", "Amber", "Yellow", "Lime", "Green", "Teal",
        "Cyan", "Sky", "Blue", "Indigo", "Purple", "Pink", "Brown", "Slate",
    };

    private ColorPicker() {
    }

    /** The swatch name for the colour, or "Custom #RRGGBB". */
    static String nameOf(int color) {
        for (int i = 0; i < SWATCHES.length; i++) {
            if (SWATCHES[i] == (color | 0xFF000000)) {
                return NAMES[i];
            }
        }
        return "Custom " + hex(color);
    }

    private static String hex(int color) {
        return String.format(Locale.US, "#%06X", color & 0xFFFFFF);
    }

    static void show(Context context, int current, IntConsumer onPick) {
        final int[] chosen = {current | 0xFF000000};
        float density = context.getResources().getDisplayMetrics().density;
        int pad = (int) (16 * density);
        // The ring around the chosen swatch follows the theme, so it shows on light and dark backgrounds.
        int ringColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0xFFFFFFFF);

        // The colour "Select" will use, shown above both tabs.
        View preview = new View(context);
        preview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        Runnable showChosen = () -> preview.setBackground(swatchBackground(chosen[0], false, density, ringColor));
        showChosen.run();

        TabLayout tabs = new TabLayout(context);
        tabs.addTab(tabs.newTab().setText("Standard"));
        tabs.addTab(tabs.newTab().setText("Custom"));

        // Standard tab: a grid of swatches; the chosen one gets a ring and is announced as selected.
        GridLayout grid = new GridLayout(context);
        grid.setColumnCount(4);
        grid.setPadding(pad, pad, pad, pad);
        View[] swatchViews = new View[SWATCHES.length];
        Runnable refreshSwatches = () -> {
            for (int j = 0; j < swatchViews.length; j++) {
                boolean on = SWATCHES[j] == chosen[0];
                swatchViews[j].setBackground(swatchBackground(SWATCHES[j], on, density, ringColor));
                swatchViews[j].setSelected(on);
                swatchViews[j].setStateDescription(on ? "Selected" : null);
            }
        };
        for (int i = 0; i < SWATCHES.length; i++) {
            final int color = SWATCHES[i];
            View s = new View(context);
            GridLayout.LayoutParams p = new GridLayout.LayoutParams();
            int size = (int) (48 * density);
            p.width = size;
            p.height = size;
            p.setMargins((int) (8 * density), (int) (8 * density), (int) (8 * density), (int) (8 * density));
            p.setGravity(Gravity.CENTER);
            s.setContentDescription(NAMES[i]);
            s.setOnClickListener(v -> {
                chosen[0] = color;
                refreshSwatches.run();
                showChosen.run();
                Effects.haptic(v, HapticFeedbackConstants.SEGMENT_TICK);
            });
            swatchViews[i] = s;
            grid.addView(s, p);
        }
        refreshSwatches.run();

        // Custom tab: hex field plus hue, saturation and brightness sliders.
        LinearLayout custom = new LinearLayout(context);
        custom.setOrientation(LinearLayout.VERTICAL);
        custom.setPadding(pad, pad, pad, pad);
        TextInputLayout hexLayout = new TextInputLayout(context);
        hexLayout.setHint("Hex colour");
        TextInputEditText hexField = new TextInputEditText(hexLayout.getContext());
        hexField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        hexField.setSingleLine(true);
        hexField.setText(hex(chosen[0]));
        hexLayout.addView(hexField, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        Slider hue = slider(context, 360, "Hue");
        Slider sat = slider(context, 100, "Saturation");
        Slider bri = slider(context, 100, "Brightness");
        float[] hsv = new float[3];
        Color.colorToHSV(chosen[0], hsv);
        hue.setValue(hsv[0]);
        sat.setValue(hsv[1] * 100);
        bri.setValue(hsv[2] * 100);
        boolean[] busy = {false};
        int[] tick = {-1};

        Slider.OnChangeListener fromSliders = (s, v, user) -> {
            if (busy[0]) {
                return;
            }
            busy[0] = true;
            chosen[0] = Color.HSVToColor(new float[] {hue.getValue(), sat.getValue() / 100f, bri.getValue() / 100f});
            hexField.setText(hex(chosen[0]));
            hexLayout.setError(null);
            showChosen.run();
            busy[0] = false;
            // A light tick every tenth of the range while dragging.
            int step = Math.round(v / 10f);
            if (user && step != tick[0]) {
                tick[0] = step;
                Effects.haptic(s, HapticFeedbackConstants.SEGMENT_FREQUENT_TICK);
            }
        };
        hue.addOnChangeListener(fromSliders);
        sat.addOnChangeListener(fromSliders);
        bri.addOnChangeListener(fromSliders);
        hexField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                if (busy[0]) {
                    return;
                }
                String t = e.toString().trim();
                try {
                    int c = Color.parseColor(t.startsWith("#") ? t : "#" + t) | 0xFF000000;
                    busy[0] = true;
                    chosen[0] = c;
                    float[] h = new float[3];
                    Color.colorToHSV(c, h);
                    hue.setValue(h[0]);
                    sat.setValue(h[1] * 100);
                    bri.setValue(h[2] * 100);
                    showChosen.run();
                    busy[0] = false;
                    hexLayout.setError(null);
                } catch (IllegalArgumentException ignored) {
                    // Half-typed: keep the last good colour and say nothing yet; once it has the length
                    // of a colour, say what is wrong.
                    boolean enough = t.replace("#", "").length() >= 6;
                    hexLayout.setError(enough ? "Use 6 characters, 0-9 and A-F, like E5383B" : null);
                }
            }
        });
        custom.addView(hexLayout);
        custom.addView(label(context, "Hue"));
        custom.addView(hue);
        custom.addView(label(context, "Saturation"));
        custom.addView(sat);
        custom.addView(label(context, "Brightness"));
        custom.addView(bri);
        custom.setVisibility(View.GONE);

        FrameLayout body = new FrameLayout(context);
        body.addView(grid);
        body.addView(custom);
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                boolean standard = tab.getPosition() == 0;
                grid.setVisibility(standard ? View.VISIBLE : View.GONE);
                custom.setVisibility(standard ? View.GONE : View.VISIBLE);
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, (int) (56 * density));
        previewParams.setMargins(pad, pad, pad, 0);
        root.addView(preview, previewParams);
        root.addView(tabs);
        root.addView(body);

        Dialogs.builder(context)
                .setTitle("Select colour")
                .setView(root)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Select", (d, w) -> onPick.accept(chosen[0]))
                .show();
    }

    private static Slider slider(Context context, float max, String name) {
        Slider s = new Slider(context);
        s.setValueFrom(0);
        s.setValueTo(max);
        s.setContentDescription(name);
        return s;
    }

    private static TextView label(Context context, String text) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0));
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); // the slider carries the name
        return t;
    }

    private static GradientDrawable swatchBackground(int color, boolean ring, float density, int ringColor) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        if (ring) {
            d.setStroke((int) (3 * density), ringColor);
        }
        return d;
    }
}
