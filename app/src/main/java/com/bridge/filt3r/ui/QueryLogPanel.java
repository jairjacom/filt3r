package com.bridge.filt3r.ui;

import android.content.Context;
import android.animation.ObjectAnimator;
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
import com.bridge.filt3r.core.util.ConnectionLog;
import com.bridge.filt3r.core.util.QueryLog;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Full-screen DNS query log, newest first. Each row shows allowed or blocked, the name and the
 * time. Chips filter by outcome, the search field by name; the switch turns logging on or off and
 * the bin clears the log.
 */
public class QueryLogPanel extends LinearLayout {

    /** What the panel needs from the app; implemented by the activity. */
    public interface Backend extends OverridesDialog.Backend {
        /** The kept lines, oldest first: "MM-dd HH:mm:ss ALLOWED|BLOCKED name", then a tab and the app when known. */
        List<String> queryLines();

        boolean queryLogEnabled();

        void setQueryLogEnabled(boolean on);

        void clearDnsLog();

        /** The kept connection lines, oldest first, in {@link ConnectionLog}'s format. */
        List<String> connectionLines();

        void clearConnectionLog();

        /** Deletes every kept connection line of the apps with these keys. */
        void removeConnectionApps(Set<String> keys);

        /** Of these app keys (package names), the ones no longer installed; hidden or system apps are never in it. */
        Set<String> removedApps(Set<String> keys);

        /** Deletes every kept line of the apps with these keys. */
        void removeQueryApps(Set<String> keys);

        /**
         * Lets the user pick a place and saves the kept lines (DNS, or connections when {@code connections})
         * of the chosen kinds there as a .txt file.
         * At least one of the two kinds is true. {@code done} gets true when the file was written, false if
         * nothing was saved (cancelled, nothing to save or an error).
         */
        void exportQueryLog(boolean connections, boolean blocked, boolean allowed, java.util.function.Consumer<Boolean> done);
    }

    private static final long REFRESH_MS = 2000;

    private static final int ALL = 0;
    private static final int BLOCKED = 1;
    private static final int ALLOWED = 2;
    private static final int TOP = 3; // names grouped and counted, most asked first

    /** One parsed log line: a DNS query, or a connection (then count is at least 1). */
    private static final class Entry {
        final String time;
        final boolean blocked;
        final String host; // a connection shows "host:port"
        final String app; // the asking app's name; null when unknown
        final boolean removed; // the asking app is no longer installed
        final String summary; // for the Top view: "Blocked 3 · Allowed 1"; null for a single query
        final String key; // the app's package name; null when unknown
        final String reason; // why a connection was refused; null otherwise
        final int count; // how many flows a connection line merged; 0 for a DNS query

        Entry(String time, boolean blocked, String host, String app, boolean removed, String summary) {
            this(time, blocked, host, app, removed, summary, null, null, 0);
        }

        Entry(String time, boolean blocked, String host, String app, boolean removed, String summary,
                String key, String reason, int count) {
            this.time = time;
            this.blocked = blocked;
            this.host = host;
            this.app = app;
            this.removed = removed;
            this.summary = summary;
            this.key = key;
            this.reason = reason;
            this.count = count;
        }
    }

    private final Backend backend;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final EntryAdapter adapter = new EntryAdapter();
    private final TextView empty;
    private final TextInputEditText search;
    private int mode = ALL;
    private boolean connections; // the visible side: app connections, else DNS queries
    private final Set<String> checkedApps = new HashSet<>(); // app keys looked up since the screen opened
    private final Set<String> uninstalledApps = new HashSet<>(); // those of them no longer installed
    private ImageButton exportButton;
    private ObjectAnimator exportSpin;

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
        clear.setOnClickListener(v -> askClear());
        bar.addView(back);
        bar.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        ImageButton rules = iconButton(context, R.drawable.ic_rules, context.getString(R.string.qlog_rules_description));
        rules.setOnClickListener(v -> OverridesDialog.show(context, backend, () -> { }));
        bar.addView(rules);
        exportButton = iconButton(context, R.drawable.ic_export, context.getString(R.string.qlog_export_description));
        exportButton.setOnClickListener(v -> askExport());
        bar.addView(exportButton);
        bar.addView(clear);
        addView(bar);

        // DNS queries or app connections.
        sides = new MaterialButtonToggleGroup(context);
        sides.setSingleSelection(true);
        sides.setSelectionRequired(true);
        sides.setSpacing(dp(8));
        MaterialButton dnsSide = chip(context, context.getString(R.string.qlog_side_dns), R.drawable.ic_log);
        connectionsSide = chip(context, context.getString(R.string.qlog_side_connections), R.drawable.ic_list);
        sides.addView(dnsSide);
        sides.addView(connectionsSide);
        sides.check(dnsSide.getId());
        sides.addOnButtonCheckedListener((group, id, checked) -> {
            if (checked) {
                connections = id == connectionsSide.getId();
                rules.setVisibility(connections ? GONE : VISIBLE); // rules are for names, not connections
                topChip.setVisibility(connections ? GONE : VISIBLE);
                if (connections && mode == TOP) {
                    chipGroup.check(allChip.getId());
                }
                reload();
            }
        });
        LayoutParams sideParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        sideParams.setMargins(dp(16), dp(4), dp(16), dp(4));
        addView(sides, sideParams);

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
        MaterialButton all = chip(context, context.getString(R.string.qlog_chip_all), R.drawable.ic_list);
        MaterialButton blocked = chip(context, context.getString(R.string.qlog_blocked), R.drawable.ic_block);
        MaterialButton allowed = chip(context, context.getString(R.string.qlog_allowed), R.drawable.ic_check);
        MaterialButton top = chip(context, context.getString(R.string.qlog_chip_top), R.drawable.ic_chart);
        chips.addView(all);
        chips.addView(blocked);
        chips.addView(allowed);
        chips.addView(top);
        chips.check(all.getId());
        chipGroup = chips;
        blockedChip = blocked;
        topChip = top;
        allChip = all;
        chips.addOnButtonCheckedListener((group, id, checked) -> {
            if (checked) {
                mode = id == blocked.getId() ? BLOCKED : id == allowed.getId() ? ALLOWED
                        : id == top.getId() ? TOP : ALL;
                reload();
            }
        });
        LayoutParams chipParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        chipParams.setMargins(dp(16), dp(4), dp(16), dp(8));
        addView(chips, chipParams);

        // Set when the Apps screen opened the log for one app; a tap clears it.
        appBanner = new TextView(context);
        appBanner.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        appBanner.setPadding(dp(20), dp(4), dp(20), dp(8));
        dim(appBanner);
        appBanner.setVisibility(GONE);
        appBanner.setOnClickListener(v -> {
            appKeys = null;
            appBanner.setVisibility(GONE);
            reload();
        });
        addView(appBanner);

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
        list.setOnItemClickListener((p, v, pos, id) -> {
            Entry e = adapter.getItem(pos);
            if (connections) {
                showConnectionDetail(e);
            } else {
                showRuleSheet(e);
            }
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            Entry e = adapter.getItem(pos);
            if (connections) {
                copyHost(e);
            } else {
                showRowMenu(e);
            }
            return true;
        });
        addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        reload();
    }

    private MaterialButtonToggleGroup chipGroup;
    private MaterialButton blockedChip;
    private MaterialButton topChip;
    private MaterialButton allChip;
    private MaterialButtonToggleGroup sides;
    private MaterialButton connectionsSide;

    /** Only the lines of these app keys are listed while this is set (see {@link #showApp}). */
    private Set<String> appKeys;
    private TextView appBanner;

    /**
     * Opens the Connections side for one app. {@code keys} are the app keys its lines use (the packages that
     * share its user ID); {@code label} is the name shown in the banner. The Apps screen opens the log this way.
     */
    public void showApp(Set<String> keys, String label) {
        appKeys = keys;
        appBanner.setText(getContext().getString(R.string.qlog_app_filter, label));
        appBanner.setVisibility(VISIBLE);
        sides.check(connectionsSide.getId());
        reload();
    }

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
                .setItems(choices, (d, which) -> exportWithSpin(which != 1, which != 0))
                .setNegativeButton(R.string.qlog_cancel, null)
                .show();
    }

    /** The export icon spins while the file is picked and written, then shows a check. */
    private void exportWithSpin(boolean blocked, boolean allowed) {
        exportSpin = ActionMorph.busy(exportButton);
        backend.exportQueryLog(connections, blocked, allowed, ok -> post(
                () -> ActionMorph.finish(exportButton, exportSpin, ok, R.drawable.ic_export)));
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    private void reload() {
        String needle = search.getText() == null ? ""
                : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<String> lines = connections ? backend.connectionLines() : backend.queryLines();
        if (appKeys != null) {
            lines = onlyAppKeys(lines);
        }
        List<Entry> shown = new ArrayList<>();
        if (mode == TOP) {
            shown = top(lines, needle);
        } else {
            Set<String> removed = removedApps(lines);
            for (int i = lines.size() - 1; i >= 0; i--) { // newest first
                Entry e = connections ? parseConnection(lines.get(i), removed) : parse(lines.get(i), removed);
                if (e == null || mode == BLOCKED && !e.blocked || mode == ALLOWED && e.blocked) {
                    continue;
                }
                if (!needle.isEmpty() && !e.host.toLowerCase(Locale.ROOT).contains(needle)
                        && (e.app == null || !e.app.toLowerCase(Locale.ROOT).contains(needle))) {
                    continue;
                }
                shown.add(e);
            }
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
                    : backend.queryLogEnabled()
                            ? (connections ? R.string.qlog_empty_no_connections : R.string.qlog_empty_no_queries)
                    : R.string.qlog_empty_off);
        }
    }

    /** The lines of the apps in {@link #appKeys}, in their order. */
    private List<String> onlyAppKeys(List<String> lines) {
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            String key = keyOf(line);
            if (key != null && appKeys.contains(key)) {
                kept.add(line);
            }
        }
        return kept;
    }

    /** Of the apps in these lines, the ones no longer installed. Each app is asked about once per screen open. */
    private Set<String> removedApps(List<String> lines) {
        Set<String> present = new HashSet<>();
        Set<String> unseen = new HashSet<>();
        for (String line : lines) {
            String key = keyOf(line);
            if (key != null) {
                present.add(key);
                if (!checkedApps.contains(key)) {
                    unseen.add(key);
                }
            }
        }
        if (!unseen.isEmpty()) {
            uninstalledApps.addAll(backend.removedApps(unseen));
            checkedApps.addAll(unseen);
        }
        present.retainAll(uninstalledApps);
        return present;
    }

    /** The app key (package name) of a line of the visible side; null when it has none. */
    private String keyOf(String line) {
        if (connections) {
            ConnectionLog.Entry e = ConnectionLog.parse(line);
            return e == null || e.appKey.isEmpty() ? null : e.appKey;
        }
        QueryLog.Entry e = QueryLog.parse(line);
        return e == null ? null : e.key;
    }

    /** The bin: clear everything on the visible side, or only the entries of apps that are no longer installed. */
    private void askClear() {
        Context context = getContext();
        String[] choices = {context.getString(R.string.qlog_clear_menu_all), context.getString(R.string.qlog_clear_menu_removed)};
        Dialogs.builder(context)
                .setTitle(R.string.qlog_clear_menu_title)
                .setItems(choices, (d, which) -> {
                    if (which == 0) {
                        Dialogs.confirm(context, context.getString(R.string.qlog_clear_title),
                                context.getString(connections ? R.string.qlog_clear_message_connections : R.string.qlog_clear_message),
                                context.getString(R.string.qlog_clear_confirm), () -> {
                                    if (connections) {
                                        backend.clearConnectionLog();
                                    } else {
                                        backend.clearDnsLog();
                                    }
                                    reload();
                                });
                    } else {
                        confirmRemoveRemoved();
                    }
                })
                .setNegativeButton(R.string.qlog_cancel, null)
                .show();
    }

    private void confirmRemoveRemoved() {
        Context context = getContext();
        Set<String> removed = removedApps(connections ? backend.connectionLines() : backend.queryLines());
        if (removed.isEmpty()) {
            Messages.show(this, context.getString(R.string.qlog_removed_none), Snackbar.LENGTH_SHORT);
            return;
        }
        Dialogs.confirm(context, context.getString(R.string.qlog_removed_title),
                context.getString(R.string.qlog_removed_message, removed.size()), context.getString(R.string.qlog_clear_confirm), () -> {
                    if (connections) {
                        backend.removeConnectionApps(removed);
                    } else {
                        backend.removeQueryApps(removed);
                    }
                    reload();
                });
    }

    private void copyHost(Entry e) {
        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                getContext().getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(android.content.ClipData.newPlainText("filt3r", e.host));
        Messages.show(this, getContext().getString(R.string.qlog_copied), Snackbar.LENGTH_SHORT);
    }

    /** What one connection line says, in words, with the package name the app is saved under. */
    private void showConnectionDetail(Entry e) {
        Context context = getContext();
        StringBuilder text = new StringBuilder();
        text.append(context.getString(R.string.qlog_detail_app, appText(context, e)));
        if (e.key != null) {
            text.append('\n').append(context.getString(R.string.qlog_detail_package, e.key));
        }
        text.append('\n').append(context.getString(e.blocked ? R.string.qlog_blocked : R.string.qlog_allowed));
        if (e.reason != null) {
            text.append('\n').append(context.getString(R.string.qlog_detail_reason, e.reason));
        }
        text.append('\n').append(e.time);
        Dialogs.builder(context)
                .setTitle(e.count > 1 ? context.getString(R.string.qlog_host_count, e.host, e.count) : e.host)
                .setMessage(text.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /** Every name once, most asked first, with how many times it was blocked and allowed. */
    private List<Entry> top(List<String> lines, String needle) {
        java.util.Map<String, int[]> counts = new java.util.HashMap<>(); // name -> {blocked, allowed}
        for (String line : lines) {
            Entry e = parse(line, Collections.<String>emptySet());
            if (e == null || !needle.isEmpty() && !e.host.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            counts.computeIfAbsent(e.host, k -> new int[2])[e.blocked ? 0 : 1]++;
        }
        List<java.util.Map.Entry<String, int[]>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort((a, b) -> {
            int totalA = a.getValue()[0] + a.getValue()[1];
            int totalB = b.getValue()[0] + b.getValue()[1];
            return totalA != totalB ? Integer.compare(totalB, totalA) : a.getKey().compareTo(b.getKey());
        });
        List<Entry> out = new ArrayList<>();
        for (java.util.Map.Entry<String, int[]> m : sorted) {
            int[] c = m.getValue();
            out.add(new Entry("", c[0] >= c[1], m.getKey(), null, false,
                    getContext().getString(R.string.qlog_top_summary, c[0], c[1])));
        }
        return out;
    }

    /** A log line as a panel row; null for a line that does not fit. */
    private static Entry parse(String line, Set<String> removed) {
        QueryLog.Entry e = QueryLog.parse(line);
        return e == null ? null : new Entry(e.time, e.blocked, e.host, e.app,
                e.key != null && removed.contains(e.key), null);
    }

    /** A connection line as a panel row; null for a line that does not fit. */
    private static Entry parseConnection(String line, Set<String> removed) {
        ConnectionLog.Entry e = ConnectionLog.parse(line);
        if (e == null) {
            return null;
        }
        String target = (e.dest.indexOf(':') >= 0 ? "[" + e.dest + "]" : e.dest) + ":" + e.port;
        boolean known = !e.appKey.isEmpty();
        return new Entry(e.time, e.blocked, target, e.label.isEmpty() ? null : e.label,
                known && removed.contains(e.appKey), null, known ? e.appKey : null, e.reason, e.count);
    }

    private MaterialButton chip(Context context, String text, int icon) {
        MaterialButton b = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(text);
        b.setId(View.generateViewId());
        b.setCheckable(true);
        Accent.selectable(b);
        Accent.morphIcon(b, icon);
        return b;
    }

    /** Long-press menu of one row: copy the name, show only that name, or open its rules. */
    private void showRowMenu(Entry e) {
        Context context = getContext();
        String[] items = {context.getString(R.string.qlog_menu_copy), context.getString(R.string.qlog_menu_only),
                context.getString(R.string.qlog_menu_rule)};
        Dialogs.builder(context)
                .setTitle(e.host)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                                context.getSystemService(Context.CLIPBOARD_SERVICE);
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("filt3r", e.host));
                        Messages.show(this, context.getString(R.string.qlog_copied), Snackbar.LENGTH_SHORT);
                    } else if (which == 1) {
                        search.setText(e.host);
                    } else {
                        showRuleSheet(e);
                    }
                })
                .setNegativeButton(R.string.qlog_cancel, null)
                .show();
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
                if (x.blocked != y.blocked || !x.time.equals(y.time) || !x.host.equals(y.host)
                        || x.count != y.count || !java.util.Objects.equals(x.reason, y.reason)
                        || !java.util.Objects.equals(x.summary, y.summary)
                        || x.removed != y.removed || !java.util.Objects.equals(x.app, y.app)) {
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
            Context ctx = getContext();
            dotBg.setColor(e.blocked ? ctx.getColor(R.color.brand_red) : Colors.active(ctx));
            host.setText(e.count > 1 ? ctx.getString(R.string.qlog_host_count, e.host, e.count) : e.host);
            String who = appText(ctx, e);
            String said = e.summary != null ? e.summary
                    : ctx.getString(e.blocked ? R.string.qlog_blocked : R.string.qlog_allowed)
                    + (e.reason == null ? "" : " · " + e.reason) + " · " + e.time
                    + (who == null ? "" : " · " + who);
            detail.setText(said);
            setContentDescription(ctx.getString(R.string.qlog_row_description, e.host,
                    ctx.getString(e.blocked ? R.string.qlog_said_blocked : R.string.qlog_said_allowed),
                    e.summary != null ? e.summary : e.time + (who == null ? "" : ", " + who)));
        }
    }

    /** The app's name for a row, marked when it is no longer installed; null for a DNS query nobody owns. */
    private static String appText(Context ctx, Entry e) {
        if (e.app == null) {
            return e.count > 0 ? ctx.getString(R.string.qlog_app_unknown) : null;
        }
        return e.removed ? ctx.getString(R.string.qlog_app_removed, e.app) : e.app;
    }
}
