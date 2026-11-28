package com.bridge.filt3r.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.bridge.filt3r.Effects;
import com.bridge.filt3r.InstalledApps;
import com.bridge.filt3r.R;
import com.bridge.filt3r.core.firewall.AppRule;
import com.bridge.filt3r.core.firewall.ConnectivityRisk;
import com.bridge.filt3r.core.firewall.ExemptTunnel;
import com.bridge.filt3r.core.firewall.UniversalToggle;
import com.bridge.filt3r.core.util.AppList;
import com.bridge.filt3r.core.util.ConnectionLog;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The Firewall screen: every installed app, A to Z, with three boxes (Wi-Fi, Mobile, Device locked) that
 * block it, and what the connection log says about it. Ticking a box applies at once. Two tabs, Apps and
 * System, each with its own list and its own three universal switches, which tick or clear one box for every
 * app in the tab. Tapping an app opens the log's Connections side for it; the "Connection log" button opens
 * it for every app. The counts are over the lines the log still holds, not lifetime totals.
 */
public class AppsPanel extends LinearLayout {

    /** What the panel needs from the app; implemented by the activity next to the log screen. */
    public interface Backend {
        /** The connection log's lines, oldest first. */
        List<String> connectionLines();

        /**
         * Closes this screen and shows the log's Connections side for one app: {@code keys} are the app
         * keys its lines use (the packages that share its user ID), {@code label} its name.
         */
        void showAppConnections(Set<String> keys, String label);

        /** Closes this screen and shows the log's Connections side for every app. */
        void showConnectionLog();

        /** The saved app rules by app key; an app with no rule is allowed. */
        Map<String, AppRule> appRules();

        /** Saves (and applies at once) one rule for every key in {@code keys}: the apps that share a user ID. */
        void setAppRule(Set<String> keys, AppRule rule);

        /** Saves (and applies at once) these rules in place of every saved rule; the universal switches change many. */
        void setAppRules(Map<String, AppRule> rules);

        /** Package names of the Exempt apps: one stays outside the tunnel until a box is ticked for it. */
        Set<String> exemptApps();
    }

    private final Backend backend;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ListAdapter adapter = new ListAdapter();
    private final TextInputEditText search;
    private boolean systemTab;
    private boolean refreshing;
    private final MaterialSwitch[] toggles = new MaterialSwitch[UniversalToggle.Box.values().length];
    private final TextView systemCard;
    private final TextView empty;
    private List<InstalledApps.App> installed = new ArrayList<>();
    private final Map<String, InstalledApps.App> byPackage = new HashMap<>();
    private List<AppList.App> apps = new ArrayList<>();
    private List<ConnectionLog.Entry> entries = new ArrayList<>();
    private boolean detached;
    private final Map<String, AppRule> rules;
    private final Set<String> exempt;

    public AppsPanel(Context context, Backend backend, Runnable onClose) {
        super(context);
        this.backend = backend;
        this.rules = new HashMap<>(backend.appRules());
        this.exempt = new HashSet<>(backend.exemptApps());
        setOrientation(VERTICAL);
        setBackgroundColor(MaterialColors.getColor(this, android.R.attr.colorBackground));
        setClickable(true); // swallow touches so the screen underneath stays still

        // Title row: back, title.
        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(8), dp(8), dp(8));
        ImageButton back = new ImageButton(context);
        back.setImageResource(R.drawable.ic_back);
        back.setContentDescription(context.getString(R.string.apps_back_description));
        back.setBackground(Ripple.borderless(context));
        back.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurface)));
        back.setOnClickListener(v -> onClose.run());
        TextView title = new TextView(context);
        title.setText(R.string.apps_title);
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        bar.addView(back, new LayoutParams(dp(48), dp(48)));
        bar.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        addView(bar);

        TextView note = new TextView(context);
        note.setText(R.string.apps_note);
        note.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        dim(note);
        note.setPadding(dp(20), 0, dp(20), dp(8));
        addView(note);

        TextView connectionLog = new TextView(context);
        connectionLog.setText(R.string.firewall_connection_log);
        connectionLog.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        connectionLog.setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary));
        connectionLog.setPadding(dp(20), dp(12), dp(20), dp(12));
        connectionLog.setBackground(Ripple.borderless(context));
        connectionLog.setOnClickListener(v -> backend.showConnectionLog());
        addView(connectionLog);

        // The System tab's warning: shown only there.
        systemCard = new TextView(context);
        systemCard.setText(R.string.firewall_system_card);
        systemCard.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        systemCard.setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnErrorContainer));
        systemCard.setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorErrorContainer));
        systemCard.setPadding(dp(20), dp(12), dp(20), dp(12));
        systemCard.setVisibility(GONE);
        // Tabs: Apps and System, each with its own list and its own switches.
        TabLayout tabs = new TabLayout(context);
        tabs.addTab(tabs.newTab().setText(R.string.firewall_tab_apps));
        tabs.addTab(tabs.newTab().setText(R.string.firewall_tab_system));
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                systemTab = tab.getPosition() == 1;
                systemCard.setVisibility(systemTab ? VISIBLE : GONE);
                applyFilter();
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });
        addView(tabs);
        addView(systemCard);
        addView(toggleRow(context, UniversalToggle.Box.WIFI, R.string.firewall_all_wifi));
        addView(toggleRow(context, UniversalToggle.Box.MOBILE, R.string.firewall_all_mobile));
        addView(toggleRow(context, UniversalToggle.Box.LOCKED, R.string.firewall_all_locked));

        // Search field.
        TextInputLayout searchLayout = new TextInputLayout(context);
        searchLayout.setHint(context.getString(R.string.exempt_apps_search_hint));
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
                applyFilter();
            }
        });
        searchLayout.addView(search, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        LayoutParams searchParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        searchParams.setMargins(dp(16), dp(4), dp(16), dp(4));
        addView(searchLayout, searchParams);

        empty = new TextView(context);
        empty.setText(R.string.exempt_apps_loading);
        empty.setGravity(Gravity.CENTER);
        dim(empty);
        empty.setPadding(dp(16), dp(48), dp(16), dp(16));
        addView(empty);

        ListView list = new ListView(context);
        list.setDivider(null);
        list.setAdapter(adapter);
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(96)); // the floating toolbar sits at the bottom
        addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        load(context);
    }

    @Override
    protected void onDetachedFromWindow() {
        detached = true;
        super.onDetachedFromWindow();
    }

    /** Reads the apps and the log off the main thread, then fills the list. */
    private void load(Context context) {
        new Thread(() -> {
            List<InstalledApps.App> found = InstalledApps.load(context);
            List<AppList.App> core = new ArrayList<>();
            Map<String, InstalledApps.App> index = new HashMap<>();
            for (InstalledApps.App a : found) {
                core.add(new AppList.App(a.pkg, a.label, a.system, a.group));
                index.put(a.pkg, a);
            }
            List<ConnectionLog.Entry> lines = new ArrayList<>();
            for (String line : backend.connectionLines()) {
                ConnectionLog.Entry e = ConnectionLog.parse(line);
                if (e != null) {
                    lines.add(e);
                }
            }
            handler.post(() -> {
                if (!detached) {
                    installed = found;
                    byPackage.putAll(index);
                    apps = core;
                    entries = lines;
                    applyFilter();
                }
            });
        }, "apps-load").start();
    }

    private void applyFilter() {
        String needle = search.getText() == null ? "" : search.getText().toString();
        List<AppList.Row> rows = AppList.build(apps, entries, systemTab, needle);
        adapter.set(rows);
        empty.setVisibility(rows.isEmpty() ? VISIBLE : GONE);
        empty.setText(installed.isEmpty() ? R.string.exempt_apps_loading : R.string.exempt_apps_no_match);
        refreshToggles();
    }

    /** One universal switch: on only while every app of the tab has the box; flipping it ticks or clears the box for all. */
    private LinearLayout toggleRow(Context context, UniversalToggle.Box box, int label) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(2), dp(20), dp(2));
        TextView text = new TextView(context);
        text.setText(label);
        text.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        MaterialSwitch toggle = new MaterialSwitch(context);
        toggle.setContentDescription(context.getString(label));
        toggle.setOnCheckedChangeListener((b, on) -> {
            if (!refreshing) {
                Effects.toggle(toggle, on);
                onUniversal(box, on);
            }
        });
        row.addView(text, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        row.addView(toggle);
        row.setOnClickListener(v -> toggle.toggle());
        toggles[box.ordinal()] = toggle;
        return row;
    }

    /** The apps of the open tab, not narrowed by the search: the shared user ID groups the switches act on. */
    private List<Set<String>> tabGroups() {
        return AppList.tabGroups(apps, systemTab);
    }

    /** A switch flipped on in the System tab asks first; the switch shows off meanwhile and cancelling changes nothing. */
    private void onUniversal(UniversalToggle.Box box, boolean on) {
        if (systemTab && on) {
            handler.post(this::refreshToggles);
            Context context = getContext();
            Dialogs.confirm(context, context.getString(R.string.firewall_system_all_title),
                    context.getString(R.string.firewall_system_all_message),
                    context.getString(R.string.firewall_system_continue), () -> universalAfterNotice(box, true));
            return;
        }
        universalAfterNotice(box, on);
    }

    /** Ticking a box for an Exempt app that has none moves it into the tunnel, which costs it calls and fast video: say so first. */
    private void universalAfterNotice(UniversalToggle.Box box, boolean on) {
        if (on && ExemptTunnel.wouldEnterTunnel(tabGroups(), exempt, rules)) {
            handler.post(this::refreshToggles);
            Context context = getContext();
            Dialogs.confirm(context, context.getString(R.string.firewall_exempt_all_title),
                    context.getString(R.string.firewall_exempt_all_message),
                    context.getString(R.string.firewall_system_continue), () -> applyUniversal(box, true));
            return;
        }
        applyUniversal(box, on);
    }

    private void applyUniversal(UniversalToggle.Box box, boolean on) {
        Map<String, AppRule> next = UniversalToggle.switchTo(box, on, tabGroups(), rules);
        rules.clear();
        rules.putAll(next);
        backend.setAppRules(next);
        adapter.notifyDataSetChanged();
        refreshToggles();
    }

    /** Sets each switch from the rules, without counting it as a tap. */
    private void refreshToggles() {
        List<Set<String>> groups = tabGroups();
        refreshing = true;
        for (UniversalToggle.Box box : UniversalToggle.Box.values()) {
            toggles[box.ordinal()].setChecked(UniversalToggle.isOn(box, groups, rules));
        }
        refreshing = false;
    }

    private static void dim(TextView t) {
        t.setTextColor(MaterialColors.getColor(t.getContext(),
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private AppRule ruleOf(AppList.Row row) {
        return rules.getOrDefault(row.app.pkg, AppRule.NONE);
    }

    /** Android leaves a whole user ID out of the tunnel when one package of it is Exempt, so the whole group counts. */
    private boolean isExempt(AppList.App app) {
        return AppList.isExempt(app, exempt);
    }

    private MaterialCheckBox box(Context context) {
        MaterialCheckBox b = new MaterialCheckBox(context);
        b.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        return b;
    }

    /** Applies a tick or an untick. The first box on a system app asks first, with the box shown unticked meanwhile. */
    private void onBox(AppList.Row row, UniversalToggle.Box which, boolean on) {
        AppRule current = ruleOf(row);
        AppRule next = which.with(current, on);
        if (row.app.system && on && current.isEmpty()) {
            handler.post(adapter::notifyDataSetChanged);
            Context context = getContext();
            Dialogs.confirm(context, row.app.label, context.getString(R.string.firewall_system_warning),
                    context.getString(R.string.firewall_system_continue), () -> ruleAfterNotice(row, next));
            return;
        }
        ruleAfterNotice(row, next);
    }

    /** The first box on an Exempt app moves it into the tunnel (calls and fast video stop working while a rule is on): say so first. */
    private void ruleAfterNotice(AppList.Row row, AppRule next) {
        if (!next.isEmpty() && ExemptTunnel.wouldEnterTunnel(List.of(row.app.group), exempt, rules)) {
            handler.post(adapter::notifyDataSetChanged);
            Context context = getContext();
            Dialogs.confirm(context, row.app.label, context.getString(R.string.firewall_exempt_notice),
                    context.getString(R.string.firewall_system_continue), () -> applyRule(row, next));
            return;
        }
        applyRule(row, next);
    }

    /** Keeps the rule for every package of the user ID, saves it and redraws (a shared ID shows in several rows). */
    private void applyRule(AppList.Row row, AppRule rule) {
        for (String key : row.app.group) {
            if (rule.isEmpty()) {
                rules.remove(key);
            } else {
                rules.put(key, rule);
            }
        }
        backend.setAppRule(row.app.group, rule);
        handler.post(adapter::notifyDataSetChanged);
        refreshToggles();
    }

    /** The other packages that share this app's user ID, A to Z; "" when it shares with nobody. */
    private String sharedPackages(AppList.Row row) {
        List<String> names = new ArrayList<>(row.app.group);
        names.remove(row.app.pkg);
        Collections.sort(names);
        return String.join(", ", names);
    }

    private final class ListAdapter extends BaseAdapter {
        private List<AppList.Row> items = new ArrayList<>();

        void set(List<AppList.Row> next) {
            items = next;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public AppList.Row getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            AppRow row = convert == null ? new AppRow(parent.getContext()) : (AppRow) convert;
            row.bind(items.get(position));
            return row;
        }
    }

    /** One app: icon, name, package, and what the log says. The row opens the log for the app. */
    private final class AppRow extends LinearLayout {
        private final ImageView icon;
        private final TextView name;
        private final TextView pkg;
        private final TextView summary;
        private final TextView shared;
        private final TextView breakTag;
        private final LinearLayout boxes;
        private final MaterialCheckBox wifi;
        private final MaterialCheckBox mobile;
        private final MaterialCheckBox locked;
        private final TextView exemptTag;

        AppRow(Context context) {
            super(context);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(20), dp(10), dp(20), dp(10));

            icon = new ImageView(context);
            LayoutParams iconParams = new LayoutParams(dp(40), dp(40));
            iconParams.rightMargin = dp(16);
            addView(icon, iconParams);

            LinearLayout texts = new LinearLayout(context);
            texts.setOrientation(VERTICAL);
            name = new TextView(context);
            name.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            pkg = new TextView(context);
            pkg.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            dim(pkg);
            pkg.setSingleLine(true);
            pkg.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            summary = new TextView(context);
            summary.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            shared = new TextView(context);
            shared.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            dim(shared);
            breakTag = new TextView(context);
            breakTag.setText(R.string.firewall_may_break);
            breakTag.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
            breakTag.setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError));
            texts.addView(name);
            texts.addView(pkg);
            texts.addView(summary);
            texts.addView(shared);
            texts.addView(breakTag);

            boxes = new LinearLayout(context);
            wifi = box(context);
            wifi.setText(R.string.firewall_box_wifi);
            mobile = box(context);
            mobile.setText(R.string.firewall_box_mobile);
            locked = box(context);
            locked.setText(R.string.firewall_box_locked);
            boxes.addView(wifi, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            boxes.addView(mobile, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            boxes.addView(locked, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            texts.addView(boxes);

            exemptTag = new TextView(context);
            exemptTag.setText(R.string.firewall_rule_exempt);
            exemptTag.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
            exemptTag.setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary));
            exemptTag.setPadding(0, dp(8), 0, dp(8));
            texts.addView(exemptTag);
            addView(texts, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        }

        void bind(AppList.Row row) {
            InstalledApps.App app = byPackage.get(row.app.pkg);
            icon.setImageDrawable(app == null ? null : app.icon);
            name.setText(row.app.system
                    ? getContext().getString(R.string.apps_name_system, row.app.label) : row.app.label);
            pkg.setText(row.app.pkg);
            if (row.lastTime == null) {
                summary.setVisibility(GONE);
            } else {
                summary.setVisibility(VISIBLE);
                String last = row.lastTime.length() >= 11 ? row.lastTime.substring(0, 11) : row.lastTime;
                summary.setText(getContext().getString(R.string.apps_summary, row.allowed, row.blocked, last));
            }
            if (row.sharedWith > 0) {
                shared.setVisibility(VISIBLE);
                shared.setText(getContext().getString(R.string.firewall_shared_with, sharedPackages(row)));
            } else {
                shared.setVisibility(GONE);
            }
            breakTag.setVisibility(ConnectivityRisk.mayBreakConnectivity(row.app.group) ? VISIBLE : GONE);
            boolean isExempt = isExempt(row.app);
            exemptTag.setVisibility(isExempt ? VISIBLE : GONE);
            exemptTag.setOnClickListener(v -> Dialogs.builder(getContext())
                    .setTitle(row.app.label)
                    .setMessage(R.string.firewall_exempt_message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show());
            AppRule rule = ruleOf(row);
            bindBox(wifi, row, rule.wifi(), UniversalToggle.Box.WIFI, R.string.firewall_box_wifi_description);
            bindBox(mobile, row, rule.mobile(), UniversalToggle.Box.MOBILE, R.string.firewall_box_mobile_description);
            bindBox(locked, row, rule.locked(), UniversalToggle.Box.LOCKED, R.string.firewall_box_locked_description);
            setOnClickListener(v -> backend.showAppConnections(row.app.group, row.app.label));
        }

        /** Shows one box without firing its listener, then listens for the user's tick. */
        private void bindBox(MaterialCheckBox b, AppList.Row row, boolean on, UniversalToggle.Box which, int description) {
            b.setOnCheckedChangeListener(null);
            b.setChecked(on);
            b.setContentDescription(getContext().getString(description, row.app.label));
            b.setOnCheckedChangeListener((view, checked) -> onBox(row, which, checked));
        }
    }
}
