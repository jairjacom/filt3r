package com.bridge.filt3r.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;

import com.bridge.filt3r.R;
import com.bridge.filt3r.core.filter.FilterCatalog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Full-screen settings in card groups: Connection, DNS and filtering, Block lists, Privacy,
 * Appearance, About.
 * Every change is saved at once. Those that only apply at the next (re)start show a "Restart to
 * apply" banner while the tunnel is up.
 */
public class SettingsPanel extends LinearLayout {

    /** One DNSCrypt resolver the user can pick. {@code millis} is -1 if it was not measured. */
    public static final class Resolver {
        public final String name;
        public final String stamp;
        public final long millis;

        public Resolver(String name, String stamp, long millis) {
            this.name = name;
            this.stamp = stamp;
            this.millis = millis;
        }
    }

    /** What the panel needs from the app; implemented by the activity next to Settings. */
    public interface Backend extends OverridesDialog.Backend {
        /** True while the tunnel is up or starting. */
        boolean running();

        void restart();

        int subscriptionSlots();

        /** "" if the slot is empty. */
        String subscriptionUrl(int slot);

        int subscriptionHours(int slot);

        void setSubscription(int slot, String url, int hours);

        boolean failClosed();

        void setFailClosed(boolean closed);

        String resolverName();

        /** The built-in resolver first, then the ones that passed the last test (fastest first). */
        List<Resolver> resolvers();

        void setResolver(String name, String stamp);

        /** Tests a stamp through the node. Blocks; the panel calls it off the main thread. */
        String checkResolver(String stamp) throws IOException;

        /** "" if none. */
        String filterListUrl();

        /** Saves the text. @return an error message to show, or null if it was saved */
        String setFilterListUrl(String text);

        /** The downloadable block lists in dnsfilter.conf order. */
        List<FilterCatalog.Entry> filterCatalog();

        /** Whether the list is on: the user's choice, or the list's default if none was made. */
        boolean filterListEnabled(String id);

        void setFilterListEnabled(String id, boolean on);

        boolean ipBlockEnabled();

        void setIpBlockEnabled(boolean on);

        /** The filter-list status, one or two lines; read every second while the panel is open. */
        String filterStatus();

        /** One line about the IP blocklist (downloading, loaded, or why not); empty when there is nothing to say. */
        String ipBlockStatus();

        /** Starts a download now. @return false if the tunnel is down */
        boolean updateFilters();

        boolean queryLogEnabled();

        void setQueryLogEnabled(boolean on);

        void clearQueryLog();

        boolean toolbarPinned();

        void setToolbarPinned(boolean pinned);

        /** "floating" or "docked". Changing it restarts the screen. */
        String toolbarStyle();

        void setToolbarStyle(String style);

        String version();

        /** 0 follow the system, 1 light, 2 dark. Applies at once. */
        int themeMode();

        void setThemeMode(int mode);

        boolean pureBlack();

        void setPureBlack(boolean on);

        /** "default", "standard", "vibrant", "expressive" or "dynamic" (Material You). */
        String colorScheme();

        void setColorScheme(String scheme);

        /** The colour the generated palette starts from, opaque ARGB. */
        int seedColor();

        void setSeedColor(int argb);
    }

    private static final int[] INTERVAL_HOURS = {1, 3, 6, 12, 24, 48, 168};
    private static final String CUSTOM_RESOLVER_NAME = "Custom stamp";
    private static final String STAMP_HELP = "Find DNSCrypt stamps (they start with sdns://AQ) in the public"
            + " resolver list at github.com/DNSCrypt/dnscrypt-resolvers (file v3/public-resolvers.md)."
            + " Only resolvers that support the XChaCha20 cipher and DNSCrypt over TCP work. The VPN has"
            + " to be on: the stamp is tested through your node before it is saved.";

    private final Backend backend;
    private final LinearLayout content;
    private final LinearLayout banner;
    private boolean changed;
    private TextView filterStatus;
    private TextView ipBlockStatus;
    private TextView ipBlockStatusInFilters;

    public SettingsPanel(Context context, Backend backend, Runnable onClose) {
        super(context);
        this.backend = backend;
        setOrientation(VERTICAL);
        setBackgroundColor(MaterialColors.getColor(this, android.R.attr.colorBackground));
        setClickable(true); // swallow touches so the screen underneath stays still

        LinearLayout bar = new LinearLayout(context);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), dp(8), dp(8), dp(8));
        ImageButton back = new ImageButton(context);
        back.setImageResource(R.drawable.ic_back);
        back.setContentDescription("Back");
        back.setBackground(null);
        back.setImageTintList(android.content.res.ColorStateList.valueOf(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorOnSurface)));
        back.setOnClickListener(v -> onClose.run());
        TextView title = new TextView(context);
        title.setText("Settings");
        title.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge);
        bar.addView(back, new LayoutParams(dp(48), dp(48)));
        bar.addView(title, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        addView(bar);

        banner = new LinearLayout(context);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(20), dp(8), dp(12), dp(8));
        banner.setBackground(rounded(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorSecondaryContainer), 24));
        TextView bannerText = new TextView(context);
        bannerText.setText("Restart to apply");
        bannerText.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        MaterialButton restart = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        restart.setText("Restart");
        restart.setOnClickListener(v -> {
            changed = false;
            updateBanner();
            backend.restart();
        });
        banner.addView(bannerText, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        banner.addView(restart);
        banner.setVisibility(GONE);
        LayoutParams bannerParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        bannerParams.setMargins(dp(16), dp(4), dp(16), dp(8));
        addView(banner, bannerParams);

        ScrollView scroll = new ScrollView(context);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, dp(96)); // the floating toolbar sits at the bottom
        content = new LinearLayout(context);
        content.setOrientation(VERTICAL);
        scroll.addView(content, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        buildConnection(context);
        buildDns(context);
        buildBlockLists(context);
        buildPrivacy(context);
        buildAppearance(context);
        buildAbout(context);
    }

    // ---- groups ----

    private void buildConnection(Context context) {
        LinearLayout card = group("Connection");
        for (int i = 0; i < backend.subscriptionSlots(); i++) {
            final int slot = i;
            final String[] url = {backend.subscriptionUrl(slot)};
            final int[] hours = {backend.subscriptionHours(slot)};
            TextInputLayout field = textField(context, "Subscription URL " + (slot + 1), url[0]);
            field.getEditText().setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            field.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE); // masked: the URL may hold a token
            Row interval = valueRow(context, "Update interval", intervalText(hours[0]));
            watch(field, text -> {
                url[0] = text.trim();
                backend.setSubscription(slot, url[0], hours[0]);
                markChanged();
            });
            interval.setOnClickListener(v -> chooseInterval(hours[0], h -> {
                hours[0] = h;
                interval.setSummary(intervalText(h));
                backend.setSubscription(slot, url[0], h);
                markChanged();
            }));
            card.addView(field, fieldParams());
            card.addView(interval);
        }
        card.addView(note(context, "Paste the address of a Shadowsocks subscription. Nodes come from it."));
        card.addView(switchRow(context, "Block traffic when no node works",
                "Off: connect directly instead, which shows your real IP.",
                backend.failClosed(), on -> {
                    backend.setFailClosed(on);
                    markChanged();
                }));
    }

    private void buildDns(Context context) {
        LinearLayout card = group("DNS and filtering");
        Row resolverRow = valueRow(context, "DNSCrypt resolver", backend.resolverName());
        resolverRow.setOnClickListener(v -> chooseResolver(resolverRow));
        card.addView(resolverRow);

        Row rules = valueRow(context, "My rules", OverridesDialog.summary(backend));
        rules.setOnClickListener(v -> OverridesDialog.show(context, backend,
                () -> rules.setSummary(OverridesDialog.summary(backend))));
        card.addView(rules);

        TextInputLayout custom = textField(context, "Custom sdns:// stamp", "");
        custom.getEditText().setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        TextView result = note(context, "");
        result.setVisibility(GONE);
        MaterialButton test = new MaterialButton(context);
        test.setText("Test and use");
        test.setOnClickListener(v -> {
            String stamp = custom.getEditText().getText().toString().trim();
            if (stamp.isEmpty()) {
                return;
            }
            test.setEnabled(false);
            result.setVisibility(VISIBLE);
            result.setText("Testing through the node...");
            Thread t = new Thread(() -> {
                String message;
                boolean ok;
                try {
                    message = backend.checkResolver(stamp);
                    ok = true;
                } catch (IOException e) {
                    message = e.getMessage();
                    ok = false;
                }
                final String shown = message;
                final boolean saved = ok;
                post(() -> {
                    test.setEnabled(true);
                    if (saved) {
                        backend.setResolver(CUSTOM_RESOLVER_NAME, stamp);
                        resolverRow.setSummary(CUSTOM_RESOLVER_NAME);
                        markChanged();
                        result.setText("Saved: " + shown);
                    } else {
                        result.setText(shown);
                    }
                });
            }, "resolver-check");
            t.setDaemon(true);
            t.start();
        });
        card.addView(custom, fieldParams());
        LayoutParams testParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        testParams.setMargins(dp(16), 0, dp(16), dp(4));
        card.addView(test, testParams);
        card.addView(result);
        card.addView(note(context, STAMP_HELP));

        TextInputLayout lists = textField(context, "Filter-list URLs", backend.filterListUrl());
        lists.getEditText().setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        lists.getEditText().setSingleLine(false);
        watch(lists, text -> {
            String error = backend.setFilterListUrl(text);
            lists.setError(error);
            if (error == null) {
                markChanged();
            }
        });
        card.addView(lists, fieldParams());
        card.addView(note(context, "Host lists that block ads and trackers (https:// only; separate several with"
                + " spaces). They are used in addition to the lists switched on under Block lists."));
        filterStatus = note(context, backend.filterStatus());
        card.addView(filterStatus);
        ipBlockStatusInFilters = note(context, "");
        card.addView(ipBlockStatusInFilters);
        MaterialButton update = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        update.setText("Update lists now");
        update.setOnClickListener(v -> Toast.makeText(context, backend.updateFilters()
                ? "Updating the filter lists..." : "Connect first: the lists are downloaded through a node.",
                Toast.LENGTH_SHORT).show());
        LayoutParams updateParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        updateParams.setMargins(dp(16), 0, dp(16), dp(8));
        card.addView(update, updateParams);
    }

    private void buildBlockLists(Context context) {
        LinearLayout card = group("Block lists");
        for (FilterCatalog.Entry entry : backend.filterCatalog()) {
            card.addView(switchRow(context, entry.id, listDescription(entry),
                    backend.filterListEnabled(entry.id), on -> {
                        backend.setFilterListEnabled(entry.id, on);
                        markChanged();
                    }));
        }
        card.addView(switchRow(context, "IP blocklist (Hagezi TIF)",
                "Hagezi IP Blocklist",
                backend.ipBlockEnabled(), on -> {
                    backend.setIpBlockEnabled(on);
                    markChanged();
                }));
        ipBlockStatus = note(context, "");
        showIpBlockStatus();
        card.addView(ipBlockStatus);
        card.addView(note(context, "Changes apply the next time you connect. Filter-list URLs above are used in"
                + " addition to these lists."));
    }

    private void showIpBlockStatus() {
        String s = backend.ipBlockStatus();
        if (s.isEmpty() && backend.ipBlockEnabled()) {
            s = "on, loads the next time you connect";
        }
        ipBlockStatus.setText("IP blocklist: " + s);
        ipBlockStatus.setVisibility(s.isEmpty() ? GONE : VISIBLE);
        ipBlockStatusInFilters.setText("IP blocklist: " + s);
        ipBlockStatusInFilters.setVisibility(s.isEmpty() ? GONE : VISIBLE);
    }

    /** The text under a list name: a readable description for the lists added with this card, else the category. */
    private static String listDescription(FilterCatalog.Entry entry) {
        switch (entry.id) {
            case "HageziProMini":
                return "Hagezi Domain Blocklist";
            case "EasyPrivacy-trackers":
                return "EasyList Tracking Servers";
            default:
                return entry.category;
        }
    }

    private void buildPrivacy(Context context) {
        LinearLayout card = group("Privacy");
        card.addView(switchRow(context, "Query log",
                "Keeps the last 1,000 DNS queries on this phone.",
                backend.queryLogEnabled(), backend::setQueryLogEnabled));
        MaterialButton clear = new MaterialButton(context, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        clear.setText("Clear query log");
        clear.setOnClickListener(v -> {
            backend.clearQueryLog();
            Toast.makeText(context, "Query log cleared", Toast.LENGTH_SHORT).show();
        });
        LayoutParams clearParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        clearParams.setMargins(dp(16), 0, dp(16), dp(8));
        card.addView(clear, clearParams);
    }

    private static final String[] SCHEME_KEYS = {"default", "standard", "vibrant", "expressive", "dynamic"};
    private static final String[] SCHEME_LABELS = {"filt3r default", "Standard", "Vibrant",
        "Expressive (accents shifted)", "Use Material You"};

    private void buildAppearance(Context context) {
        LinearLayout card = group("Appearance");

        // Theme mode: follow the system, light or dark. Applies at once (the system restarts the screen).
        TextView modeLabel = new TextView(context);
        modeLabel.setText("Theme");
        modeLabel.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        modeLabel.setPadding(dp(20), dp(12), dp(20), dp(4));
        card.addView(modeLabel);
        MaterialButtonToggleGroup modes = new MaterialButtonToggleGroup(context);
        modes.setSingleSelection(true);
        modes.setSelectionRequired(true);
        String[] modeNames = {"System", "Light", "Dark"};
        int[] modeIds = new int[3];
        for (int i = 0; i < 3; i++) {
            MaterialButton b = new MaterialButton(context, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            b.setText(modeNames[i]);
            b.setId(View.generateViewId());
            b.setCheckable(true);
            modeIds[i] = b.getId();
            modes.addView(b);
        }
        modes.check(modeIds[Math.max(0, Math.min(2, backend.themeMode()))]);
        modes.addOnButtonCheckedListener((group, id, checked) -> {
            if (checked) {
                for (int i = 0; i < 3; i++) {
                    if (modeIds[i] == id && i != backend.themeMode()) {
                        backend.setThemeMode(i);
                    }
                }
            }
        });
        LayoutParams modeParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        modeParams.setMargins(dp(16), 0, dp(16), dp(8));
        card.addView(modes, modeParams);

        card.addView(switchRow(context, "Pure black",
                "A black background in the dark theme.", backend.pureBlack(), backend::setPureBlack));

        Row scheme = valueRow(context, "Colour scheme", schemeLabel(backend.colorScheme()));
        scheme.setOnClickListener(v -> chooseScheme());
        card.addView(scheme);
        if ("expressive".equals(backend.colorScheme())) {
            card.addView(note(context, "Expressive keeps your colour in the background and shifts the buttons and"
                    + " accents to other hues. Use Standard or Vibrant to colour the buttons with your choice."));
        }

        boolean dynamic = "dynamic".equals(backend.colorScheme());
        Row colour = valueRow(context, "Select colour", "");
        colour.setSummary(swatchText(backend.seedColor()));
        if (dynamic) {
            colour.setAlpha(0.4f); // the wallpaper picks the colours
            colour.setClickable(false);
        } else {
            colour.setOnClickListener(v -> ColorPicker.show(context, backend.seedColor(), picked -> {
                backend.setSeedColor(picked);
                if ("default".equals(backend.colorScheme())) {
                    backend.setColorScheme("standard"); // a chosen colour means a generated palette
                }
            }));
        }
        card.addView(colour);

        // Toolbar style: a floating pill that can hide, or a docked bar with swipe between sections.
        TextView toolbarLabel = new TextView(context);
        toolbarLabel.setText("Toolbar");
        toolbarLabel.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        toolbarLabel.setPadding(dp(20), dp(12), dp(20), dp(4));
        card.addView(toolbarLabel);
        MaterialButtonToggleGroup styles = new MaterialButtonToggleGroup(context);
        styles.setSingleSelection(true);
        styles.setSelectionRequired(true);
        String[] styleKeys = {"floating", "docked"};
        String[] styleNames = {"Floating", "Docked"};
        int[] styleIds = new int[2];
        for (int i = 0; i < 2; i++) {
            MaterialButton b = new MaterialButton(context, null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle);
            b.setText(styleNames[i]);
            b.setId(View.generateViewId());
            b.setCheckable(true);
            styleIds[i] = b.getId();
            styles.addView(b);
        }
        boolean dockedStyle = "docked".equals(backend.toolbarStyle());
        styles.check(styleIds[dockedStyle ? 1 : 0]);
        styles.addOnButtonCheckedListener((group, id, checked) -> {
            if (checked) {
                for (int i = 0; i < 2; i++) {
                    if (styleIds[i] == id && !styleKeys[i].equals(backend.toolbarStyle())) {
                        backend.setToolbarStyle(styleKeys[i]);
                    }
                }
            }
        });
        LayoutParams styleParams = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        styleParams.setMargins(dp(16), 0, dp(16), dp(4));
        card.addView(styles, styleParams);
        card.addView(note(context, dockedStyle
                ? "Docked: the toolbar stays on screen. Swipe left or right to move between Status, Nodes,"
                + " Exempt apps, Query log and Settings."
                : "Floating: the toolbar hides when idle and opens each section over the Status screen."));

        if (!dockedStyle) {
            card.addView(switchRow(context, "Pin the toolbar",
                    "Keep the bottom toolbar visible instead of hiding it when idle.",
                    backend.toolbarPinned(), backend::setToolbarPinned));
        }
    }

    private static String schemeLabel(String key) {
        for (int i = 0; i < SCHEME_KEYS.length; i++) {
            if (SCHEME_KEYS[i].equals(key)) {
                return SCHEME_LABELS[i];
            }
        }
        return SCHEME_LABELS[0];
    }

    /** "● Name" with the dot in the colour itself. */
    private static CharSequence swatchText(int color) {
        SpannableString s = new SpannableString("●  " + ColorPicker.nameOf(color));
        s.setSpan(new ForegroundColorSpan(color | 0xFF000000), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    private void chooseScheme() {
        boolean dynamicOk = DynamicColors.isDynamicColorAvailable();
        List<String> keys = new ArrayList<>();
        List<CharSequence> labels = new ArrayList<>();
        for (int i = 0; i < SCHEME_KEYS.length; i++) {
            if (dynamicOk || !"dynamic".equals(SCHEME_KEYS[i])) {
                keys.add(SCHEME_KEYS[i]);
                labels.add(SCHEME_LABELS[i]);
            }
        }
        new MaterialAlertDialogBuilder(getContext())
                .setTitle("Colour scheme")
                .setSingleChoiceItems(labels.toArray(new CharSequence[0]), keys.indexOf(backend.colorScheme()),
                        (d, which) -> {
                            d.dismiss();
                            if (!keys.get(which).equals(backend.colorScheme())) {
                                backend.setColorScheme(keys.get(which));
                            }
                        })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void buildAbout(Context context) {
        LinearLayout card = group("About");
        card.addView(note(context, "filt3r " + backend.version()));
        card.addView(note(context, "For the strongest protection, turn on Always-on VPN and Block connections"
                + " without VPN for filt3r in Android Settings > Network > VPN. Only one VPN can run at a"
                + " time: filt3r replaces any other."));
        card.addView(note(context, "filt3r is free software, GPL version 2 or later, built on PersonalDNSFilter."
                + " The APK is conveyed under GPL version 3 because it includes Material Components (Apache"
                + " License 2.0). It also includes hev-socks5-tunnel (MIT), lwIP (BSD 3-clause) and libyaml"
                + " (MIT)."));
    }

    private final Runnable statusTick = new Runnable() {
        @Override
        public void run() {
            filterStatus.setText(backend.filterStatus());
            showIpBlockStatus();
            postDelayed(this, 1000);
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(statusTick);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(statusTick);
        super.onDetachedFromWindow();
    }

    // ---- behaviour ----

    private void markChanged() {
        changed = true;
        updateBanner();
    }

    private void updateBanner() {
        banner.setVisibility(changed && backend.running() ? VISIBLE : GONE);
    }

    private void chooseInterval(int current, java.util.function.IntConsumer onPick) {
        CharSequence[] items = new CharSequence[INTERVAL_HOURS.length];
        int checked = -1;
        for (int i = 0; i < items.length; i++) {
            items[i] = intervalText(INTERVAL_HOURS[i]);
            if (INTERVAL_HOURS[i] == current) {
                checked = i;
            }
        }
        new MaterialAlertDialogBuilder(getContext())
                .setTitle("Update interval")
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    onPick.accept(INTERVAL_HOURS[which]);
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void chooseResolver(Row row) {
        List<Resolver> all = backend.resolvers();
        List<CharSequence> items = new ArrayList<>();
        String current = backend.resolverName();
        int checked = -1;
        for (int i = 0; i < all.size(); i++) {
            Resolver r = all.get(i);
            items.add(r.millis >= 0 ? r.name + "  ·  " + r.millis + " ms" : r.name);
            if (r.name.equals(current)) {
                checked = i;
            }
        }
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(getContext())
                .setTitle("DNSCrypt resolver")
                .setSingleChoiceItems(items.toArray(new CharSequence[0]), checked, (d, which) -> {
                    Resolver r = all.get(which);
                    backend.setResolver(r.name, r.stamp);
                    row.setSummary(r.name);
                    markChanged();
                    d.dismiss();
                })
                .setNegativeButton("Cancel", null);
        if (all.size() <= 1) {
            b.setMessage("More resolvers appear here after the VPN has run for a few minutes.");
        }
        b.show();
    }

    private static String intervalText(int hours) {
        if (hours == 1) {
            return "Every hour";
        }
        if (hours < 24) {
            return "Every " + hours + " hours";
        }
        if (hours == 24) {
            return "Every day";
        }
        if (hours % 24 == 0 && hours < 168) {
            return "Every " + hours / 24 + " days";
        }
        return hours == 168 ? "Every week" : "Every " + hours + " hours";
    }

    // ---- building blocks ----

    private interface TextListener {
        void onText(String text);
    }

    private interface SwitchListener {
        void onChecked(boolean on);
    }

    /** Calls the listener on every edit made after this point. */
    private static void watch(TextInputLayout field, TextListener listener) {
        field.getEditText().addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                listener.onText(s.toString());
            }
        });
    }

    private LinearLayout group(String title) {
        Context context = getContext();
        TextView label = new TextView(context);
        label.setText(title);
        label.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge);
        label.setTextColor(MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary));
        LayoutParams labelParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        labelParams.setMargins(dp(28), dp(16), dp(16), dp(8));
        content.addView(label, labelParams);

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(VERTICAL);
        card.setPadding(0, dp(8), 0, dp(8));
        card.setBackground(rounded(MaterialColors.getColor(this,
                com.google.android.material.R.attr.colorSurfaceContainer), 24));
        LayoutParams cardParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(dp(16), 0, dp(16), 0);
        content.addView(card, cardParams);
        return card;
    }

    private TextInputLayout textField(Context context, String hint, String value) {
        TextInputLayout layout = new TextInputLayout(context);
        layout.setHint(hint);
        TextInputEditText edit = new TextInputEditText(layout.getContext());
        edit.setText(value);
        layout.addView(edit, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        return layout;
    }

    private LayoutParams fieldParams() {
        LayoutParams p = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(16), dp(8), dp(16), dp(4));
        return p;
    }

    private TextView note(Context context, String text) {
        TextView t = new TextView(context);
        t.setText(text);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        t.setAlpha(0.7f);
        t.setPadding(dp(20), dp(8), dp(20), dp(8));
        return t;
    }

    private LinearLayout switchRow(Context context, String title, String summary, boolean checked,
            SwitchListener listener) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(20), dp(8), dp(20), dp(8));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(VERTICAL);
        TextView t = new TextView(context);
        t.setText(title);
        t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
        TextView s = new TextView(context);
        s.setText(summary);
        s.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
        s.setAlpha(0.7f);
        texts.addView(t);
        texts.addView(s);
        MaterialSwitch sw = new MaterialSwitch(context);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener((b, on) -> listener.onChecked(on));
        row.addView(texts, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        row.addView(sw);
        row.setOnClickListener(v -> sw.toggle());
        return row;
    }

    private Row valueRow(Context context, String title, String summary) {
        return new Row(context, title, summary);
    }

    /** A tappable row: title with the current value under it. */
    private final class Row extends LinearLayout {
        private final TextView summary;

        Row(Context context, String title, String value) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(dp(20), dp(12), dp(20), dp(12));
            TextView t = new TextView(context);
            t.setText(title);
            t.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge);
            summary = new TextView(context);
            summary.setText(value);
            summary.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium);
            summary.setAlpha(0.7f);
            addView(t);
            addView(summary);
            setClickable(true);
            android.util.TypedValue tv = new android.util.TypedValue();
            context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            setBackgroundResource(tv.resourceId);
        }

        void setSummary(CharSequence value) {
            summary.setText(value);
        }
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
