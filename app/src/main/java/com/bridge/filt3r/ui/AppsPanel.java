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
import com.bridge.filt3r.core.util.AppList;
import com.bridge.filt3r.core.util.ConnectionLog;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Full-screen list of every installed app with what the connection log says about it: how many
 * connections were allowed and blocked, and when the last one was. Apps with connections come first.
 * The counts are over the lines the log still holds, not lifetime totals. Tapping an app opens the
 * log's Connections side for it. System apps are hidden unless the switch at the top shows them.
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
    }

    private final Backend backend;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ListAdapter adapter = new ListAdapter();
    private final TextInputEditText search;
    private final MaterialSwitch showSystem;
    private final TextView empty;
    private List<InstalledApps.App> installed = new ArrayList<>();
    private final Map<String, InstalledApps.App> byPackage = new HashMap<>();
    private List<AppList.App> apps = new ArrayList<>();
    private List<ConnectionLog.Entry> entries = new ArrayList<>();
    private boolean detached;

    public AppsPanel(Context context, Backend backend, Runnable onClose) {
        super(context);
        this.backend = backend;
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

        // Show-system switch.
        LinearLayout systemRow = new LinearLayout(context);
        systemRow.setGravity(Gravity.CENTER_VERTICAL);
        systemRow.setPadding(dp(20), dp(4), dp(20), dp(4));
        TextView systemText = new TextView(context);
        systemText.setText(R.string.exempt_apps_show_system);
        systemText.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        showSystem = new MaterialSwitch(context);
        showSystem.setContentDescription(context.getString(R.string.exempt_apps_show_system));
        showSystem.setOnCheckedChangeListener((b, on) -> {
            Effects.toggle(showSystem, on);
            applyFilter();
        });
        systemRow.addView(systemText, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        systemRow.addView(showSystem);
        systemRow.setOnClickListener(v -> showSystem.toggle());
        addView(systemRow);

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
        List<AppList.Row> rows = AppList.build(apps, entries, showSystem.isChecked(), needle);
        adapter.set(rows);
        empty.setVisibility(rows.isEmpty() ? VISIBLE : GONE);
        empty.setText(installed.isEmpty() ? R.string.exempt_apps_loading : R.string.exempt_apps_no_match);
    }

    private static void dim(TextView t) {
        t.setTextColor(MaterialColors.getColor(t.getContext(),
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
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
            texts.addView(name);
            texts.addView(pkg);
            texts.addView(summary);
            texts.addView(shared);
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
                shared.setText(getResources().getQuantityString(R.plurals.apps_shared, row.sharedWith, row.sharedWith));
            } else {
                shared.setVisibility(GONE);
            }
            setOnClickListener(v -> backend.showAppConnections(row.app.group, row.app.label));
        }
    }
}
