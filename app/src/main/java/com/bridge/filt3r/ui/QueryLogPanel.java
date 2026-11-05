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
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
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
        ImageButton back = iconButton(context, R.drawable.ic_back, "Back");
        back.setOnClickListener(v -> onClose.run());
        TextView title = new TextView(context);
        title.setText("Query log");
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        ImageButton clear = iconButton(context, R.drawable.ic_delete, "Clear query log");
        clear.setOnClickListener(v -> {
            backend.clearQueryLog();
            reload();
        });
        bar.addView(back);
        bar.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        ImageButton rules = iconButton(context, R.drawable.ic_rules, "My rules");
        rules.setOnClickListener(v -> OverridesDialog.show(context, backend, () -> { }));
        bar.addView(rules);
        ImageButton export = iconButton(context, R.drawable.ic_export, "Save query log as a text file");
        export.setOnClickListener(v -> askExport());
        bar.addView(export);
        bar.addView(clear);
        addView(bar);

        // On/off switch.
        LinearLayout switchRow = new LinearLayout(context);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        switchRow.setPadding(dp(20), dp(4), dp(20), dp(4));
        TextView switchText = new TextView(context);
        switchText.setText("Log DNS queries");
        switchText.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        MaterialSwitch sw = new MaterialSwitch(context);
        sw.setChecked(backend.queryLogEnabled());
        sw.setOnCheckedChangeListener((b, on) -> {
            backend.setQueryLogEnabled(on);
            reload();
        });
        switchRow.addView(switchText, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        switchRow.addView(sw);
        switchRow.setOnClickListener(v -> sw.toggle());
        addView(switchRow);

        // Search field.
        TextInputLayout searchLayout = new TextInputLayout(context);
        searchLayout.setHint("Search names");
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
        MaterialButton all = chip(context, "All");
        MaterialButton blocked = chip(context, "Blocked");
        MaterialButton allowed = chip(context, "Allowed");
        chips.addView(all);
        chips.addView(blocked);
        chips.addView(allowed);
        chips.check(all.getId());
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

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        handler.postDelayed(tick, REFRESH_MS);
    }


    /** Asks which entries to save, then hands over to the backend (which opens the file picker). */
    private void askExport() {
        String[] choices = {"Blocked", "Allowed", "Both"};
        new MaterialAlertDialogBuilder(getContext())
                .setTitle("Save which entries?")
                .setItems(choices, (d, which) -> backend.exportQueryLog(which != 1, which != 0))
                .setNegativeButton("Cancel", null)
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
            empty.setText(!lines.isEmpty() ? "Nothing matches."
                    : backend.queryLogEnabled() ? "No queries yet." : "The query log is off.");
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
            Messages.show(this, "Could not read your rules", Snackbar.LENGTH_SHORT);
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
        state.setText(rule == null ? (e.blocked ? "Blocked by the filter lists" : "Allowed")
                : rule ? "You allowed this domain" : "You blocked this domain");
        state.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        dim(state);
        content.addView(name);
        content.addView(state);

        MaterialButton action = new MaterialButton(context);
        if (rule == null) {
            boolean allow = e.blocked;
            action.setText(allow ? "Allow this domain" : "Block this domain");
            action.setOnClickListener(v -> applyRule(sheet, () -> backend.setRule(e.host, allow),
                    (allow ? "Allowed " : "Blocked ") + e.host));
        } else {
            action.setText("Remove my rule");
            action.setOnClickListener(v -> applyRule(sheet, () -> {
                backend.removeRule(e.host);
                return e.host;
            }, "Rule removed for " + e.host));
        }
        LayoutParams actionParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        actionParams.topMargin = dp(16);
        content.addView(action, actionParams);

        TextView note = new TextView(context);
        note.setText("A rule covers this domain and its sub domains and applies at once."
                + " Manage all rules with the rules icon at the top.");
        note.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        dim(note);
        note.setPadding(0, dp(12), 0, 0);
        content.addView(note);

        sheet.setContentView(content);
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
            Messages.showHere(sheet.getWindow().getDecorView(), "Could not save the rule", Snackbar.LENGTH_SHORT);
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
            dotBg.setColor(getContext().getColor(e.blocked ? R.color.brand_red : R.color.state_active));
            host.setText(e.host);
            detail.setText((e.blocked ? "Blocked" : "Allowed") + " · " + e.time);
        }
    }
}
