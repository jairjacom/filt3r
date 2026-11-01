package com.bridge.filt3r;

import android.content.Context;
import android.content.SharedPreferences;

import com.bridge.filt3r.core.socks.FailureMode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Every user setting, in one private preferences file. The Settings screen reads and writes
 * through this class; the service reads it when the tunnel starts, so a change applies after
 * the next (re)start.
 */
final class Settings {

    static final int MAX_SUBSCRIPTIONS = 3;
    static final int DEFAULT_INTERVAL_HOURS = 24;

    /**
     * The built-in resolver: dnscry.pt in Norway, "no query logs, no intentional filtering". It also
     * serves as the resolver that tests nodes, so a bad user choice cannot make every node look dead.
     */
    static final String DEFAULT_RESOLVER_NAME = "dnscry.pt (default)";
    static final String DEFAULT_RESOLVER_STAMP =
            "sdns://AQcAAAAAAAAADjE5OC4xNDAuMTQxLjQ2IFqbafOxgXuKwOgYxQ6XUqHWkMUt_5LI2nDkdVFU5hm7GTIuZG5zY3J5cHQtY2VydC5kbnNjcnkucHQ";

    /**
     * The same dnscry.pt resolver (same provider key and name) at its IPv6 address. Reaching it
     * through a node proves the node has IPv6 egress.
     */
    static final String DEFAULT_RESOLVER_STAMP_V6 =
            "sdns://AQcAAAAAAAAAFlsyYTAzOjk0ZTM6MjIyYjo6MTAzMl0gWptp87GBe4rA6BjFDpdSodaQxS3_ksjacOR1UVTmGbsZMi5kbnNjcnlwdC1jZXJ0LmRuc2NyeS5wdA";

    /** One subscription slot. Intervals are whole hours, at least 1. */
    static final class Subscription {
        final int slot;
        final String url;
        final int intervalHours;

        Subscription(int slot, String url, int intervalHours) {
            this.slot = slot;
            this.url = url;
            this.intervalHours = intervalHours;
        }
    }

    private static final String FILE = "filt3r";
    private static final String KEY_FAILURE_MODE = "failure_mode";
    private static final String KEY_QUERY_LOG = "query_log";
    private static final String KEY_EXEMPT_APPS = "exempt_apps";
    private static final String KEY_RESOLVER_NAME = "resolver_name";
    private static final String KEY_RESOLVER_STAMP = "resolver_stamp";
    private static final String KEY_PINNED_NODE = "pinned_node";
    private static final String KEY_NODE_SORT_SPEED = "node_sort_speed";
    private static final String KEY_FILTER_URL = "filter_url";
    private static final String KEY_TOOLBAR_PINNED = "toolbar_pinned";
    private static final String KEY_TOOLBAR_STYLE = "toolbar_style";
    private static final String KEY_THEME_MODE = "theme_mode";
    private static final String KEY_PURE_BLACK = "pure_black";
    private static final String KEY_COLOR_SCHEME = "color_scheme";
    private static final String KEY_SEED_COLOR = "seed_color";

    private final SharedPreferences prefs;

    Settings(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Subscription slots that have a URL, in slot order. */
    List<Subscription> subscriptions() {
        List<Subscription> out = new ArrayList<>();
        for (int i = 0; i < MAX_SUBSCRIPTIONS; i++) {
            String url = prefs.getString("sub_url_" + i, "").trim();
            if (!url.isEmpty()) {
                out.add(new Subscription(i, url,
                        Math.max(1, prefs.getInt("sub_hours_" + i, DEFAULT_INTERVAL_HOURS))));
            }
        }
        return out;
    }

    /** An empty URL clears the slot. */
    void setSubscription(int slot, String url, int intervalHours) {
        if (slot < 0 || slot >= MAX_SUBSCRIPTIONS) {
            throw new IllegalArgumentException("slot out of range");
        }
        prefs.edit()
                .putString("sub_url_" + slot, url.trim())
                .putInt("sub_hours_" + slot, Math.max(1, intervalHours))
                .apply();
    }

    /** The chosen DNSCrypt resolver's display name. Applies at the next (re)start. */
    String resolverName() {
        return prefs.getString(KEY_RESOLVER_NAME, DEFAULT_RESOLVER_NAME);
    }

    /** The chosen resolver's sdns:// stamp; the built-in default if none was chosen. */
    String resolverStamp() {
        return prefs.getString(KEY_RESOLVER_STAMP, DEFAULT_RESOLVER_STAMP);
    }

    void setResolver(String name, String stamp) {
        prefs.edit().putString(KEY_RESOLVER_NAME, name).putString(KEY_RESOLVER_STAMP, stamp).apply();
    }

    FailureMode failureMode() {
        try {
            return FailureMode.valueOf(prefs.getString(KEY_FAILURE_MODE, FailureMode.FAIL_CLOSED.name()));
        } catch (IllegalArgumentException e) {
            return FailureMode.FAIL_CLOSED; // unknown value: take the safe default
        }
    }

    void setFailureMode(FailureMode mode) {
        prefs.edit().putString(KEY_FAILURE_MODE, mode.name()).apply();
    }

    boolean queryLogEnabled() {
        return prefs.getBoolean(KEY_QUERY_LOG, false); // off until the user turns it on: queried names are not recorded by default
    }

    void setQueryLogEnabled(boolean on) {
        prefs.edit().putBoolean(KEY_QUERY_LOG, on).apply();
    }

    /** Key of the pinned node (see NodeManager.keyOf), or null. Applies once that node has answered a test. */
    String pinnedNode() {
        return prefs.getString(KEY_PINNED_NODE, null);
    }

    void setPinnedNode(String key) {
        prefs.edit().putString(KEY_PINNED_NODE, key).apply();
    }

    /** True to sort the Nodes panel by measured speed instead of latency. */
    boolean nodeSortBySpeed() {
        return prefs.getBoolean(KEY_NODE_SORT_SPEED, false);
    }

    void setNodeSortBySpeed(boolean bySpeed) {
        prefs.edit().putBoolean(KEY_NODE_SORT_SPEED, bySpeed).apply();
    }

    /** Package names that bypass the tunnel (they use the real IP and the real DNS). */
    Set<String> exemptApps() {
        return new HashSet<>(prefs.getStringSet(KEY_EXEMPT_APPS, new HashSet<>()));
    }

    void setExemptApps(Set<String> packages) {
        prefs.edit().putStringSet(KEY_EXEMPT_APPS, new HashSet<>(packages)).apply();
    }

    /**
     * The user's filter-list URL(s), "; "-separated, or "" to use the lists configured in
     * dnsfilter.conf. Applies at the next (re)start.
     */
    String filterListUrl() {
        return prefs.getString(KEY_FILTER_URL, "");
    }

    /** @param urls already checked with {@link #normalizeFilterUrls}; "" clears */
    void setFilterListUrl(String urls) {
        prefs.edit().putString(KEY_FILTER_URL, urls).apply();
    }

    /**
     * Splits on whitespace, commas and semicolons, requires https, and joins with "; ".
     *
     * @throws IllegalArgumentException naming the first bad entry
     */
    static String normalizeFilterUrls(String text) {
        StringBuilder out = new StringBuilder();
        for (String url : text.trim().split("[\\s,;]+")) {
            if (url.isEmpty()) {
                continue;
            }
            if (!url.regionMatches(true, 0, "https://", 0, 8) || url.length() == 8) {
                throw new IllegalArgumentException("Only https:// addresses work: " + url);
            }
            if (out.length() > 0) {
                out.append("; ");
            }
            out.append(url);
        }
        return out.toString();
    }

    /** True to keep the floating toolbar always visible instead of hiding when idle. */
    boolean toolbarPinned() {
        return prefs.getBoolean(KEY_TOOLBAR_PINNED, false);
    }

    void setToolbarPinned(boolean pinned) {
        prefs.edit().putBoolean(KEY_TOOLBAR_PINNED, pinned).apply();
    }

    static final String TOOLBAR_FLOATING = "floating";
    static final String TOOLBAR_DOCKED = "docked";

    /** TOOLBAR_FLOATING (a pill that can hide) or TOOLBAR_DOCKED (always shown, swipe between sections). */
    String toolbarStyle() {
        return TOOLBAR_DOCKED.equals(prefs.getString(KEY_TOOLBAR_STYLE, TOOLBAR_FLOATING))
                ? TOOLBAR_DOCKED : TOOLBAR_FLOATING;
    }

    void setToolbarStyle(String style) {
        prefs.edit().putString(KEY_TOOLBAR_STYLE, style).apply();
    }

    static final String SCHEME_DEFAULT = "default";
    static final String SCHEME_STANDARD = "standard";
    static final String SCHEME_VIBRANT = "vibrant";
    static final String SCHEME_EXPRESSIVE = "expressive";
    static final String SCHEME_DYNAMIC = "dynamic";
    static final int DEFAULT_SEED = 0xFFE5383B;

    /** android.app.UiModeManager night mode: 0 follow the system, 1 light, 2 dark. */
    int themeMode() {
        return prefs.getInt(KEY_THEME_MODE, 0);
    }

    void setThemeMode(int mode) {
        prefs.edit().putInt(KEY_THEME_MODE, mode).apply();
    }

    /** True for a pure black (#000000) canvas in the dark theme. */
    boolean pureBlack() {
        return prefs.getBoolean(KEY_PURE_BLACK, false);
    }

    void setPureBlack(boolean on) {
        prefs.edit().putBoolean(KEY_PURE_BLACK, on).apply();
    }

    /** One of the SCHEME_ constants. SCHEME_DEFAULT is the fixed filt3r palette. */
    String colorScheme() {
        return prefs.getString(KEY_COLOR_SCHEME, SCHEME_DEFAULT);
    }

    void setColorScheme(String scheme) {
        prefs.edit().putString(KEY_COLOR_SCHEME, scheme).apply();
    }

    /** The colour the generated palette starts from (opaque ARGB). */
    int seedColor() {
        return prefs.getInt(KEY_SEED_COLOR, DEFAULT_SEED);
    }

    void setSeedColor(int argb) {
        prefs.edit().putInt(KEY_SEED_COLOR, argb | 0xFF000000).apply();
    }
}
