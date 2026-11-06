package com.bridge.filt3r.ui;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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
import com.bridge.filt3r.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Full-screen list of installed apps with a switch each. A switched-on app is exempt: it skips the
 * tunnel and uses the real IP and the real DNS. Exempt apps come first, then A to Z. System apps
 * are hidden unless the switch at the top shows them (an exempt one is always shown). The new
 * list is read when the tunnel starts, so a running tunnel shows "Restart to apply".
 */
public class ExemptAppsPanel extends LinearLayout {

    /** What the panel needs from the app; implemented by the activity next to Settings. */
    public interface Backend {
        /** Package names that are exempt. */
        Set<String> exemptApps();

        void setExemptApps(Set<String> packages);

        /** True while the tunnel is up or starting. */
        boolean running();

        void restart();

        /** Whether a change is waiting for a restart; shared with Settings so the dock can show a dot. */
        boolean restartPending();

        void setRestartPending(boolean pending);
    }

    /** One installed app with a launcher icon. */
    private static final class App {
        final String pkg;
        final String label;
        final boolean system;
        final Drawable icon;

        App(String pkg, String label, boolean system, Drawable icon) {
            this.pkg = pkg;
            this.label = label;
            this.system = system;
            this.icon = icon;
        }
    }

    private final Backend backend;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> exempt;
    private final AppAdapter adapter = new AppAdapter();
    private final TextInputEditText search;
    private final MaterialSwitch showSystem;
    private final TextView empty;
    private final LinearLayout banner;
    private List<App> apps = new ArrayList<>();
    private boolean detached;
    /** Per shown app: exempt flag, package and label; the list is only redrawn when this changes. */
    private List<String> shownKey = new ArrayList<>();

    public ExemptAppsPanel(Context context, Backend backend, Runnable onClose) {
        super(context);
        this.backend = backend;
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
        back.setContentDescription("Back");
        back.setBackground(Ripple.borderless(context));
        back.setImageTintList(ColorStateList.valueOf(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurface)));
        back.setOnClickListener(v -> onClose.run());
        TextView title = new TextView(context);
        title.setText("Exempt apps");
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        bar.addView(back, new LayoutParams(dp(48), dp(48)));
        bar.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        addView(bar);

        TextView note = new TextView(context);
        note.setText("Exempt apps bypass filt3r: real IP, real DNS, no filtering. Use it for calls and messaging.");
        note.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        dim(note);
        note.setPadding(dp(20), 0, dp(20), dp(8));
        addView(note);

        // Restart banner, shown only while the tunnel runs and the list changed.
        banner = new LinearLayout(context);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(20), dp(8), dp(12), dp(8));
        GradientDrawable bannerBg = new GradientDrawable();
        bannerBg.setColor(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorSecondaryContainer));
        bannerBg.setCornerRadius(dp(24));
        banner.setBackground(bannerBg);
        TextView bannerText = new TextView(context);
        bannerText.setText("Restart to apply");
        bannerText.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        MaterialButton restart = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        restart.setText("Restart");
        restart.setOnClickListener(v -> {
            banner.setVisibility(GONE);
            backend.setRestartPending(false);
            backend.restart();
        });
        banner.addView(bannerText, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        banner.addView(restart);
        banner.setVisibility(backend.restartPending() && backend.running() ? VISIBLE : GONE);
        LayoutParams bannerParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        bannerParams.setMargins(dp(16), dp(4), dp(16), dp(8));
        addView(banner, bannerParams);

        // Search field.
        TextInputLayout searchLayout = new TextInputLayout(context);
        searchLayout.setHint("Search apps");
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
        systemText.setText("Show system apps");
        systemText.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        showSystem = new MaterialSwitch(context);
        showSystem.setContentDescription("Show system apps");
        showSystem.setOnCheckedChangeListener((b, on) -> {
            Effects.toggle(showSystem, on);
            applyFilter();
        });
        systemRow.addView(systemText, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        systemRow.addView(showSystem);
        systemRow.setOnClickListener(v -> showSystem.toggle());
        addView(systemRow);

        empty = new TextView(context);
        empty.setText("Loading apps...");
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

        loadApps(context);
    }

    @Override
    protected void onDetachedFromWindow() {
        detached = true;
        super.onDetachedFromWindow();
    }

    /** Reads the launchable apps off the main thread, then fills the list. */
    private void loadApps(Context context) {
        PackageManager pm = context.getPackageManager();
        String own = context.getPackageName();
        new Thread(() -> {
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            Map<String, App> byPackage = new HashMap<>();
            for (ResolveInfo ri : pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)) {
                ApplicationInfo info = ri.activityInfo.applicationInfo;
                if (info.packageName.equals(own) || byPackage.containsKey(info.packageName)) {
                    continue;
                }
                boolean system = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (info.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
                byPackage.put(info.packageName, new App(info.packageName,
                        String.valueOf(ri.loadLabel(pm)), system, ri.loadIcon(pm)));
            }
            List<App> found = new ArrayList<>(byPackage.values());
            handler.post(() -> {
                if (!detached) {
                    apps = found;
                    applyFilter();
                }
            });
        }, "exempt-apps-load").start();
    }

    /** Rebuilds the visible list: filter by search and system switch, exempt first, then A to Z. */
    private void applyFilter() {
        String needle = search.getText() == null ? ""
                : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        boolean withSystem = showSystem.isChecked();
        List<App> shown = new ArrayList<>();
        for (App a : apps) {
            if (a.system && !withSystem && !exempt.contains(a.pkg)) {
                continue;
            }
            if (!needle.isEmpty() && !a.label.toLowerCase(Locale.ROOT).contains(needle)
                    && !a.pkg.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            shown.add(a);
        }
        Collections.sort(shown, (x, y) -> {
            boolean ex = exempt.contains(x.pkg);
            boolean ey = exempt.contains(y.pkg);
            if (ex != ey) {
                return ex ? -1 : 1;
            }
            return x.label.compareToIgnoreCase(y.label);
        });
        List<String> key = new ArrayList<>();
        for (App a : shown) {
            key.add((exempt.contains(a.pkg) ? "1" : "0") + a.pkg + "|" + a.label);
        }
        if (!key.equals(shownKey)) {
            shownKey = key;
            adapter.set(shown);
        }
        if (shown.isEmpty() && empty.getVisibility() != VISIBLE
                && Effects.on(getContext(), Effects.Effect.TRANSITIONS)) {
            empty.setAlpha(0f);
            empty.animate().alpha(1f).setDuration(Effects.ms(getContext(), Effects.Effect.TRANSITIONS, 200));
        }
        empty.setVisibility(shown.isEmpty() ? VISIBLE : GONE);
        empty.setText(apps.isEmpty() ? "Loading apps..." : "Nothing matches.");
    }

    private void toggle(App app, boolean on) {
        if (on) {
            exempt.add(app.pkg);
        } else {
            exempt.remove(app.pkg);
        }
        backend.setExemptApps(exempt);
        backend.setRestartPending(backend.running());
        banner.setVisibility(backend.running() ? VISIBLE : GONE);
    }

    private static void dim(TextView t) {
        t.setTextColor(MaterialColors.getColor(t.getContext(),
                com.google.android.material.R.attr.colorOnSurfaceVariant, 0));
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private final class AppAdapter extends BaseAdapter {
        private List<App> items = new ArrayList<>();

        void set(List<App> next) {
            items = next;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public App getItem(int position) {
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

    /** One app: icon, name and package, switch. The row toggles the switch. */
    private final class Row extends LinearLayout {
        private final ImageView icon;
        private final TextView name;
        private final TextView pkg;
        private final MaterialSwitch sw;

        Row(Context context) {
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
            texts.addView(name);
            texts.addView(pkg);
            addView(texts, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

            sw = new MaterialSwitch(context);
            addView(sw);
            setOnClickListener(v -> sw.toggle());
        }

        void bind(App app) {
            icon.setImageDrawable(app.icon);
            name.setText(app.label);
            pkg.setText(app.pkg);
            sw.setOnCheckedChangeListener(null); // a recycled row must not report a change
            sw.setChecked(exempt.contains(app.pkg));
            sw.setContentDescription(app.label); // the switch is announced with the app's name
            sw.setOnCheckedChangeListener((b, on) -> {
                Effects.toggle(sw, on);
                toggle(app, on);
            });
        }
    }
}
