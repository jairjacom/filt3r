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
    private static final String KEY_ALLOW_IPV6 = "allow_ipv6";
    private static final String KEY_QUERY_LOG = "query_log";
    private static final String KEY_EXEMPT_APPS = "exempt_apps";

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

    /** Off until a node is known to have IPv6 egress. */
    boolean allowIpv6() {
        return prefs.getBoolean(KEY_ALLOW_IPV6, false);
    }

    boolean queryLogEnabled() {
        return prefs.getBoolean(KEY_QUERY_LOG, true);
    }

    void setQueryLogEnabled(boolean on) {
        prefs.edit().putBoolean(KEY_QUERY_LOG, on).apply();
    }

    /** Package names that bypass the tunnel (they use the real IP and the real DNS). */
    Set<String> exemptApps() {
        return new HashSet<>(prefs.getStringSet(KEY_EXEMPT_APPS, new HashSet<>()));
    }

    void setExemptApps(Set<String> packages) {
        prefs.edit().putStringSet(KEY_EXEMPT_APPS, new HashSet<>(packages)).apply();
    }
}
