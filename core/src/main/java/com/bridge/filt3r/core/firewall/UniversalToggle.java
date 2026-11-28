package com.bridge.filt3r.core.firewall;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The universal toggles of the Firewall screen: one per box, over the apps of one tab. A toggle is on only
 * while every app in the tab has its box ticked; switching it on ticks the box for all of them, switching it
 * off clears it for all of them. It is a bulk action, not a standing rule: apps installed later get no rule.
 *
 * <p>An app is a "group": the app keys (packages) that share one user ID, which always change together.
 */
public final class UniversalToggle {

    private UniversalToggle() {
    }

    /** One of the three boxes of an app rule. */
    public enum Box {
        WIFI, MOBILE, LOCKED;

        /** True when {@code rule} has this box ticked. */
        public boolean in(AppRule rule) {
            switch (this) {
                case WIFI:
                    return rule.wifi();
                case MOBILE:
                    return rule.mobile();
                default:
                    return rule.locked();
            }
        }

        /** {@code rule} with this box ticked or cleared. */
        public AppRule with(AppRule rule, boolean on) {
            switch (this) {
                case WIFI:
                    return rule.withWifi(on);
                case MOBILE:
                    return rule.withMobile(on);
                default:
                    return rule.withLocked(on);
            }
        }
    }

    /**
     * True when every key of every group has {@code box} ticked in {@code rules} (a key with no rule has none
     * ticked). False for no groups: a toggle over an empty tab has nothing to switch on.
     */
    public static boolean isOn(Box box, Collection<Set<String>> groups, Map<String, AppRule> rules) {
        if (groups.isEmpty()) {
            return false;
        }
        for (Set<String> group : new HashSet<>(groups)) {
            for (String key : group) {
                if (!box.in(rules.getOrDefault(key, AppRule.NONE))) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * The saved rules after switching {@code box} on or off for every key of every group. Other boxes and
     * keys outside the groups are kept; a rule left with no box is removed. {@code rules} is not changed.
     */
    public static Map<String, AppRule> switchTo(Box box, boolean on, Collection<Set<String>> groups,
            Map<String, AppRule> rules) {
        Map<String, AppRule> next = new HashMap<>(rules);
        for (Set<String> group : groups) {
            for (String key : group) {
                AppRule rule = box.with(next.getOrDefault(key, AppRule.NONE), on);
                if (rule.isEmpty()) {
                    next.remove(key);
                } else {
                    next.put(key, rule);
                }
            }
        }
        return next;
    }
}
