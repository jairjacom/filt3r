package com.bridge.filt3r.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.app.Activity;
import com.google.android.material.snackbar.Snackbar;

import com.bridge.filt3r.Effects;
import com.bridge.filt3r.R;
import com.bridge.filt3r.core.filter.UserOverrides;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.IOException;
import java.util.List;

/**
 * "My rules": the user's own allow and block rules for single domains. Add a domain with Allow or
 * Block, remove a rule with the bin. A rule covers the domain and its sub domains and applies at
 * once while the tunnel runs.
 */
public final class OverridesDialog {

    /** What the dialog and the Query log sheet need from the app. */
    public interface Backend {
        List<UserOverrides.Entry> rules() throws IOException;

        /** TRUE = allowed, FALSE = blocked, null = no rule for this exact domain. */
        Boolean ruleFor(String host) throws IOException;

        /**
         * Allows or blocks a domain.
         *
         * @return the domain as stored
         * @throws IllegalArgumentException if the text is not a domain name
         */
        String setRule(String host, boolean allow) throws IOException;

        void removeRule(String host) throws IOException;
    }

    private OverridesDialog() {
    }

    /** One-line summary for a Settings row. */
    public static String summary(Context context, Backend backend) {
        try {
            int n = backend.rules().size();
            return n == 0 ? context.getString(R.string.overrides_summary_none)
                    : context.getResources().getQuantityString(R.plurals.overrides_summary_rules, n, n);
        } catch (IOException e) {
            return context.getString(R.string.overrides_summary_unreadable);
        }
    }

    /** Shows the dialog; {@code onClosed} runs when it goes away (to refresh a summary). */
    public static void show(Context context, Backend backend, Runnable onClosed) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(context, 24);
        content.setPadding(pad, dp(context, 8), pad, 0);

        TextInputLayout field = new TextInputLayout(context);
        field.setHint(context.getString(R.string.overrides_domain_hint));
        TextInputEditText edit = new TextInputEditText(field.getContext());
        edit.setSingleLine(true);
        edit.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        field.addView(edit, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        content.addView(field);

        LinearLayout buttons = new LinearLayout(context);
        buttons.setGravity(Gravity.END);
        MaterialButton allow = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        allow.setText(R.string.overrides_allow);
        MaterialButton block = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        block.setText(R.string.overrides_block);
        buttons.addView(allow);
        buttons.addView(block);
        content.addView(buttons);

        TextView note = new TextView(context);
        note.setText(R.string.overrides_note);
        note.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        dim(note);
        note.setPadding(0, dp(context, 4), 0, dp(context, 8));
        content.addView(note);

        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(list);
        content.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(context, 240)));

        Runnable refill = new Runnable() {
            @Override
            public void run() {
                list.removeAllViews();
                List<UserOverrides.Entry> rules;
                try {
                    rules = backend.rules();
                } catch (IOException e) {
                    list.addView(label(context, context.getString(R.string.overrides_read_failed)));
                    return;
                }
                if (rules.isEmpty()) {
                    list.addView(label(context, context.getString(R.string.overrides_empty)));
                }
                for (UserOverrides.Entry e : rules) {
                    list.addView(row(context, e, () -> {
                        try {
                            backend.removeRule(e.host);
                            Effects.haptic(list, HapticFeedbackConstants.CONFIRM);
                            Messages.showHere(content, context.getString(R.string.overrides_removed, e.host),
                                    context.getString(R.string.overrides_undo), () -> {
                                try {
                                    backend.setRule(e.host, e.allow);
                                } catch (IOException | IllegalArgumentException ex) {
                                    Messages.showHere(content, context.getString(R.string.overrides_restore_failed), Snackbar.LENGTH_SHORT);
                                }
                                this.run();
                            });
                        } catch (IOException ex) {
                            Messages.showHere(content, context.getString(R.string.overrides_remove_failed), Snackbar.LENGTH_SHORT);
                        }
                        this.run();
                    }));
                }
            }
        };

        View.OnClickListener add = v -> {
            boolean allowIt = v == allow;
            try {
                String host = backend.setRule(edit.getText() == null ? "" : edit.getText().toString(), allowIt);
                edit.setText("");
                field.setError(null);
                Effects.haptic(content, HapticFeedbackConstants.CONFIRM);
                Messages.showHere(content, context.getString(allowIt ? R.string.overrides_allowed : R.string.overrides_blocked, host), Snackbar.LENGTH_SHORT);
                refill.run();
            } catch (IllegalArgumentException e) {
                field.setError(context.getString(R.string.overrides_invalid_domain));
                Effects.haptic(content, HapticFeedbackConstants.REJECT);
            } catch (IOException e) {
                Messages.showHere(content, context.getString(R.string.overrides_save_failed), Snackbar.LENGTH_SHORT);
            }
        };
        allow.setOnClickListener(add);
        block.setOnClickListener(add);
        refill.run();

        Dialogs.builder(context)
                .setTitle(R.string.overrides_title)
                .setView(content)
                .setPositiveButton(R.string.overrides_close, null)
                .setOnDismissListener(d -> onClosed.run())
                .show();
    }

    private static TextView label(Context context, String text) {
        TextView t = new TextView(context);
        t.setText(text);
        dim(t);
        t.setPadding(0, dp(context, 8), 0, dp(context, 8));
        return t;
    }

    /** One rule: mark and domain, bin on the right. */
    private static View row(Context context, UserOverrides.Entry e, Runnable remove) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView mark = new TextView(context);
        mark.setText(e.allow ? R.string.overrides_allow : R.string.overrides_block);
        mark.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
        mark.setTextColor(e.allow ? Colors.active(context) : context.getColor(R.color.brand_red));
        row.addView(mark, new LinearLayout.LayoutParams(dp(context, 52), LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView host = new TextView(context);
        host.setText(e.host);
        host.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        host.setSingleLine(true);
        host.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        row.addView(host, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ImageButton bin = new ImageButton(context);
        bin.setImageResource(R.drawable.ic_delete);
        bin.setContentDescription(context.getString(R.string.overrides_remove_description, e.host));
        bin.setBackground(Ripple.borderless(context));
        bin.setImageTintList(ColorStateList.valueOf(host.getCurrentTextColor()));
        bin.setOnClickListener(v -> remove.run());
        row.addView(bin, new LinearLayout.LayoutParams(dp(context, 48), dp(context, 48)));
        return row;
    }

    private static void dim(TextView t) {
        t.setTextColor(MaterialColors.getColor(t.getContext(),
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0));
    }

    private static int dp(Context context, int v) {
        return (int) (v * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
