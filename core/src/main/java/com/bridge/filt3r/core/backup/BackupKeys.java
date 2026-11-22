package com.bridge.filt3r.core.backup;

import java.util.Set;

/**
 * The preference keys a settings backup carries. Subscription addresses, the pinned node, hidden
 * nodes and any history are never in it; subscriptions go in only when the user asks.
 * <p>
 * Every key here needs a case in {@code BackupFormat.checkSetting}, or a restore silently drops it
 * as "a setting from a newer version"; {@code BackupKeysTest} fails when the two lists differ.
 * Keys starting with {@value #EFFECT_PREFIX} (one switch per effect) are also carried.
 */
public final class BackupKeys {

    public static final String EFFECT_PREFIX = "fx_";

    public static final Set<String> KEYS = Set.of(
            "failure_mode", "query_log", "resolver_name", "resolver_stamp", "node_sort_speed", "filter_url",
            "enabled_lists", "ip_block", "test_only_cheap", "list_refresh_days", "toolbar_pinned",
            "toolbar_style", "theme_mode", "pure_black", "color_scheme", "seed_color", "effects_level",
            "transition_style", "switch_faster");

    private BackupKeys() {
    }

    /** True if a backup carries this preference. */
    public static boolean carries(String key) {
        return KEYS.contains(key) || key.startsWith(EFFECT_PREFIX);
    }
}
