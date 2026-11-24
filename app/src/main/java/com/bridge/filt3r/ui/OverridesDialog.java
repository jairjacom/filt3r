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
 * "My rules": the user's own allow and block rules for domains, and custom answers (a name that
 * gets a fixed IP address). Add a domain with Allow or Block, or with an address and Answer; remove
 * an entry with the bin. A rule covers the domain and its sub domains, an answer one exact name; both
 * accept a "*" wildcard and apply at once while the tunnel runs.
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

        /**
         * Answers a name with a fixed IP address.
         *
         * @return the name as stored
         * @throws IllegalArgumentException if the name or the address is not valid
         */
        String setAnswer(String host, String ip) throws IOException;

        void removeAnswer(String host) throws IOException;
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

        TextInputLayout ipField = new TextInputLayout(context);
        ipField.setHint(context.getString(R.string.overrides_ip_hint));
        TextInputEditText ipEdit = new TextInputEditText(ipField.getContext());
        ipEdit.setSingleLine(true);
        ipEdit.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        ipField.addView(ipEdit, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        content.addView(ipField);

        LinearLayout buttons = new LinearLayout(context);
        buttons.setGravity(Gravity.END);
        MaterialButton allow = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        allow.setText(R.string.overrides_allow);
        MaterialButton block = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        block.setText(R.string.overrides_block);
        MaterialButton answer = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        answer.setText(R.string.overrides_answer);
        buttons.addView(allow);
        buttons.addView(block);
        buttons.addView(answer);
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
                            if (e.isAnswer()) {
                                backend.removeAnswer(e.host);
                            } else {
                                backend.removeRule(e.host);
                            }
                            Effects.haptic(list, HapticFeedbackConstants.CONFIRM);
                            Messages.showHere(content, context.getString(R.string.overrides_removed, e.host),
                                    context.getString(R.string.overrides_undo), () -> {
                                try {
                                    if (e.isAnswer()) {
                                        backend.setAnswer(e.host, e.ip);
                                    } else {
                                        backend.setRule(e.host, e.allow);
                                    }
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
            String name = edit.getText() == null ? "" : edit.getText().toString();
            field.setError(null);
            ipField.setError(null);
            try {
                if (v == answer) {
                    String ip = ipEdit.getText() == null ? "" : ipEdit.getText().toString();
                    try {
                        ip = UserOverrides.normaliseIp(ip);
                    } catch (IllegalArgumentException e) {
                        ipField.setError(context.getString(R.string.overrides_invalid_ip));
                        Effects.haptic(content, HapticFeedbackConstants.REJECT);
                        return;
                    }
                    String host = backend.setAnswer(name, ip);
                    ipEdit.setText("");
                    Messages.showHere(content, context.getString(R.string.overrides_answered, host, ip), Snackbar.LENGTH_SHORT);
                } else {
                    boolean allowIt = v == allow;
                    String host = backend.setRule(name, allowIt);
                    Messages.showHere(content, context.getString(allowIt ? R.string.overrides_allowed : R.string.overrides_blocked, host), Snackbar.LENGTH_SHORT);
                }
                edit.setText("");
                Effects.haptic(content, HapticFeedbackConstants.CONFIRM);
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
        answer.setOnClickListener(add);
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
        mark.setText(e.isAnswer() ? R.string.overrides_answer : e.allow ? R.string.overrides_allow : R.string.overrides_block);
        mark.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium);
        if (e.isAnswer()) {
            dim(mark);
        } else {
            mark.setTextColor(e.allow ? Colors.active(context) : context.getColor(R.color.brand_red));
        }
        row.addView(mark, new LinearLayout.LayoutParams(dp(context, 60), LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView host = new TextView(context);
        host.setText(e.isAnswer() ? context.getString(R.string.overrides_answer_row, e.host, e.ip) : e.host);
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
