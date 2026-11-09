package com.bridge.filt3r.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.google.android.material.snackbar.Snackbar;

import com.bridge.filt3r.Effects;
import com.bridge.filt3r.R;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen DNS query log, newest first. Each row shows allowed or blocked, the name and the
 * time. Chips filter by outcome, the search field by name; the switch turns logging on or off and
 * the bin clears the log.
 */
public class QueryLogPanel extends LinearLayout {

    /** What the panel needs from the app; implemented by the activity. */
    public interface Backend extends OverridesDialog.Backend {
        /** The kept lines, oldest first: "MM-dd HH:mm:ss ALLOWED|BLOCKED name". */
        List<String> queryLines();

        boolean queryLogEnabled();

        void setQueryLogEnabled(boolean on);

        void clearQueryLog();

        /**
         * Lets the user pick a place and saves the kept lines of the chosen kinds there as a .txt file.
         * At least one of the two is true.
         */
        void exportQueryLog(boolean blocked, boolean allowed);
    }

    private static final long REFRESH_MS = 2000;
    private static final int TIME_LENGTH = "MM-dd HH:mm:ss".length();

    private static final int ALL = 0;
    private static final int BLOCKED = 1;
    private static final int ALLOWED = 2;

    /** One parsed log line. */
    private static final class Entry {
        final String time;
        final boolean blocked;
        final String host;

        Entry(String time, boolean blocked, String host) {
            this.time = time;
            this.blocked = blocked;
            this.host = host;
        }
    }

    private final Backend backend;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final EntryAdapter adapter = new EntryAdapter();
    private final TextView empty;
    private final TextInputEditText search;
    private int mode = ALL;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            reload();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    public QueryLogPanel(Context context, Backend backend, Runnable onClose) {
        super(context);
        this.backend = backend;
        setOrientation(VERTICAL);
        setBackgroundColor(MaterialColors.getColor(this, android.R.attr.colorBackground));
        setClickable(true); // swallow touches so the screen underneath stays still

        // Title row: back, title, clear.
        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(8), dp(8), dp(8));
        ImageButton back = iconButton(context, R.drawable.ic_back, context.getString(R.string.qlog_back));
        back.setOnClickListener(v -> onClose.run());
        TextView title = new TextView(context);
        title.setText(R.string.qlog_title);
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        ImageButton clear = iconButton(context, R.drawable.ic_delete, context.getString(R.string.qlog_clear_description));
        clear.setOnClickListener(v -> Dialogs.confirm(getContext(), context.getString(R.string.qlog_clear_title),
                context.getString(R.string.qlog_clear_message), context.getString(R.string.qlog_clear_confirm), () -> {
                    backend.clearQueryLog();
                    reload();
                }));
        bar.addView(back);
        bar.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        ImageButton rules = iconButton(context, R.drawable.ic_rules, context.getString(R.string.qlog_rules_description));
        rules.setOnClickListener(v -> OverridesDialog.show(context, backend, () -> { }));
        bar.addView(rules);
        ImageButton export = iconButton(context, R.drawable.ic_export, context.getString(R.string.qlog_export_description));
        export.setOnClickListener(v -> askExport());
        bar.addView(export);
        bar.addView(clear);
        addView(bar);

        // On/off switch.
        LinearLayout switchRow = new LinearLayout(context);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        switchRow.setPadding(dp(20), dp(4), dp(20), dp(4));
        TextView switchText = new TextView(context);
        switchText.setText(R.string.qlog_switch);
        switchText.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        MaterialSwitch sw = new MaterialSwitch(context);
        sw.setChecked(backend.queryLogEnabled());
        sw.setContentDescription(context.getString(R.string.qlog_switch));
        sw.setOnCheckedChangeListener((b, on) -> {
            Effects.toggle(sw, on);
            backend.setQueryLogEnabled(on);
            reload();
        });
        switchRow.addView(switchText, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        switchRow.addView(sw);
        switchRow.setOnClickListener(v -> sw.toggle());
        addView(switchRow);

        // Search field.
        TextInputLayout searchLayout = new TextInputLayout(context);
        searchLayout.setHint(context.getString(R.string.qlog_search_hint));
        search = new TextInputEditText(searchLayout.getContext());
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                reload();
            }
        });
        searchLayout.addView(search, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        LayoutParams searchParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        searchParams.setMargins(dp(16), dp(4), dp(16), dp(4));
        addView(searchLayout, searchParams);

        // Outcome filter.
        MaterialButtonToggleGroup chips = new MaterialButtonToggleGroup(context);
        chips.setSingleSelection(true);
        chips.setSelectionRequired(true);
        chips.setSpacing(dp(8));
        MaterialButton all = chip(context, context.getString(R.string.qlog_chip_all));
        MaterialButton blocked = chip(context, context.getString(R.string.qlog_blocked));
        MaterialButton allowed = chip(context, context.getString(R.string.qlog_allowed));
        chips.addView(all);
        chips.addView(blocked);
        chips.addView(allowed);
        chips.check(all.getId());
        chipGroup = chips;
        blockedChip = blocked;
        chips.addOnButtonCheckedListener((group, id, checked) -> {
            if (checked) {
                mode = id == blocked.getId() ? BLOCKED : id == allowed.getId() ? ALLOWED : ALL;
                reload();
            }
        });
        LayoutParams chipParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        chipParams.setMargins(dp(16), dp(4), dp(16), dp(8));
        addView(chips, chipParams);

        empty = new TextView(context);
        empty.setGravity(Gravity.CENTER);
        dim(empty);
        empty.setPadding(dp(16), dp(48), dp(16), dp(16));
        addView(empty);

        ListView list = new ListView(context);
        list.setDivider(null);
        list.setAdapter(adapter);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(96)); // the toolbar overlays the bottom
        list.setOnItemClickListener((p, v, pos, id) -> showRuleSheet(adapter.getItem(pos)));
        addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        reload();
    }

    private MaterialButtonToggleGroup chipGroup;
    private MaterialButton blockedChip;

    /** Switches to the Blocked filter; the DNS card's "View blocked" opens the log this way. */
    public void showBlockedOnly() {
        chipGroup.check(blockedChip.getId());
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.postDelayed(tick, REFRESH_MS);
    }


    /** Asks which entries to save, then hands over to the backend (which opens the file picker). */
    private void askExport() {
        Context context = getContext();
        String[] choices = {context.getString(R.string.qlog_blocked), context.getString(R.string.qlog_allowed),
                context.getString(R.string.qlog_export_both)};
        Dialogs.builder(context)
                .setTitle(R.string.qlog_export_title)
                .setItems(choices, (d, which) -> backend.exportQueryLog(which != 1, which != 0))
                .setNegativeButton(R.string.qlog_cancel, null)
                .show();
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    private void reload() {
        String needle = search.getText() == null ? ""
                : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<String> lines = backend.queryLines();
        List<Entry> shown = new ArrayList<>();
        for (int i = lines.size() - 1; i >= 0; i--) { // newest first
            Entry e = parse(lines.get(i));
            if (e == null || mode == BLOCKED && !e.blocked || mode == ALLOWED && e.blocked) {
                continue;
            }
            if (!needle.isEmpty() && !e.host.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            shown.add(e);
        }
        adapter.set(shown);
        if (!shown.isEmpty()) {
            empty.setVisibility(GONE);
        } else {
            if (empty.getVisibility() != VISIBLE && Effects.on(getContext(), Effects.Effect.TRANSITIONS)) {
                empty.setAlpha(0f);
                empty.animate().alpha(1f)
                        .setDuration(Effects.ms(getContext(), Effects.Effect.TRANSITIONS, 200));
            }
            empty.setVisibility(VISIBLE);
            empty.setText(!lines.isEmpty() ? R.string.qlog_empty_no_match
                    : backend.queryLogEnabled() ? R.string.qlog_empty_no_queries
                    : R.string.qlog_empty_off);
        }
    }

    /** "MM-dd HH:mm:ss ALLOWED name" -> Entry; null for a line that does not fit. */
    private static Entry parse(String line) {
        if (line.length() < TIME_LENGTH + 9) {
            return null;
        }
        String time = line.substring(0, TIME_LENGTH);
        String rest = line.substring(TIME_LENGTH + 1);
        int space = rest.indexOf(' ');
        if (space < 0) {
            return null;
        }
        String verdict = rest.substring(0, space);
        if (!verdict.equals("BLOCKED") && !verdict.equals("ALLOWED")) {
            return null;
        }
        return new Entry(time, verdict.equals("BLOCKED"), rest.substring(space + 1));
    }

    private MaterialButton chip(Context context, String text) {
        MaterialButton b = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(text);
        b.setId(View.generateViewId());
        b.setCheckable(true);
        Accent.selectable(b);
        return b;
    }

    private ImageButton iconButton(Context context, int res, String description) {
        ImageButton b = new ImageButton(context);
        b.setImageResource(res);
        b.setContentDescription(description);
        b.setBackground(Ripple.borderless(context));
        b.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurface)));
        b.setLayoutParams(new LayoutParams(dp(48), dp(48)));
        return b;
    }

    /** Bottom sheet for one query: allow a blocked name, block an allowed one, or drop the user's rule. */
    private void showRuleSheet(Entry e) {
        Context context = getContext();
        Boolean rule;
        try {
            rule = backend.ruleFor(e.host);
        } catch (IOException ex) {
            Messages.show(this, context.getString(R.string.qlog_rules_read_failed), Snackbar.LENGTH_SHORT);
            return;
        }
        BottomSheetDialog sheet = new BottomSheetDialog(context);
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(VERTICAL);
        content.setPadding(dp(24), dp(20), dp(24), dp(24));

        TextView name = new TextView(context);
        name.setText(e.host);
        name.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        TextView state = new TextView(context);
        state.setText(rule == null ? (e.blocked ? R.string.qlog_state_blocked_by_lists : R.string.qlog_allowed)
                : rule ? R.string.qlog_state_you_allowed : R.string.qlog_state_you_blocked);
        state.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        dim(state);
        content.addView(name);
        content.addView(state);

        MaterialButton action = new MaterialButton(context);
        if (rule == null) {
            boolean allow = e.blocked;
            action.setText(allow ? R.string.qlog_action_allow : R.string.qlog_action_block);
            action.setOnClickListener(v -> applyRule(sheet, () -> backend.setRule(e.host, allow),
                    context.getString(allow ? R.string.qlog_toast_allowed : R.string.qlog_toast_blocked, e.host)));
        } else {
            action.setText(R.string.qlog_action_remove);
            action.setOnClickListener(v -> applyRule(sheet, () -> {
                backend.removeRule(e.host);
                return e.host;
            }, context.getString(R.string.qlog_toast_removed, e.host)));
        }
        LayoutParams actionParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        actionParams.topMargin = dp(16);
        content.addView(action, actionParams);

        TextView note = new TextView(context);
        note.setText(R.string.qlog_note);
        note.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        dim(note);
        note.setPadding(0, dp(12), 0, 0);
        content.addView(note);

        sheet.setContentView(content);
        Dialogs.blur(sheet);
        sheet.show();
    }

    private interface RuleAction {
        String run() throws IOException;
    }

    private void applyRule(BottomSheetDialog sheet, RuleAction action, String done) {
        try {
            action.run();
            Messages.show(this, done, Snackbar.LENGTH_SHORT);
            sheet.dismiss();
        } catch (IOException | IllegalArgumentException ex) {
            Messages.showHere(sheet.getWindow().getDecorView(), getContext().getString(R.string.qlog_rule_save_failed), Snackbar.LENGTH_SHORT);
        }
    }

    private static void dim(TextView t) {
        t.setTextColor(MaterialColors.getColor(t.getContext(),
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private final class EntryAdapter extends BaseAdapter {
        private List<Entry> items = new ArrayList<>();

        void set(List<Entry> next) {
            if (sameItems(items, next)) {
                return;
            }
            items = next;
            notifyDataSetChanged();
        }

        /** Entry has no equals(): compare time, verdict and host. */
        private boolean sameItems(List<Entry> a, List<Entry> b) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                Entry x = a.get(i);
                Entry y = b.get(i);
                if (x.blocked != y.blocked || !x.time.equals(y.time) || !x.host.equals(y.host)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Entry getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            Row row = convert == null ? new Row(parent.getContext()) : (Row) convert;
            row.bind(items.get(position));
            return row;
        }
    }

    /** One query: coloured dot, name, time and verdict underneath. */
    private final class Row extends LinearLayout {
        private final GradientDrawable dotBg = new GradientDrawable();
        private final TextView host;
        private final TextView detail;

        Row(Context context) {
            super(context);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(20), dp(10), dp(20), dp(10));

            dotBg.setShape(GradientDrawable.OVAL);
            View dot = new View(context);
            dot.setBackground(dotBg);
            dot.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); // the detail line says the state
            LayoutParams dotParams = new LayoutParams(dp(10), dp(10));
            dotParams.rightMargin = dp(16);
            addView(dot, dotParams);

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(VERTICAL);
            host = new TextView(context);
            host.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
            host.setSingleLine(true);
            host.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            detail = new TextView(context);
            detail.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            dim(detail);
            texts.addView(host);
            texts.addView(detail);
            addView(texts, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        }

        void bind(Entry e) {
            dotBg.setColor(e.blocked ? getContext().getColor(R.color.brand_red) : Colors.active(getContext()));
            host.setText(e.host);
            Context ctx = getContext();
            String said = ctx.getString(e.blocked ? R.string.qlog_blocked : R.string.qlog_allowed) + " · " + e.time;
            detail.setText(said);
            setContentDescription(ctx.getString(R.string.qlog_row_description, e.host,
                    ctx.getString(e.blocked ? R.string.qlog_said_blocked : R.string.qlog_said_allowed), e.time));
        }
    }
}
