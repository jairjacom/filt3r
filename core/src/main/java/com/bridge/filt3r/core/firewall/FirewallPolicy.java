package com.bridge.filt3r.core.firewall;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The firewall's decision, kept free of Android so it can be tested on the JVM. Order, as in RethinkDNS: the
 * app rule first, then the universal rules; the first match wins. An unexpected error blocks.
 * Apps that are Exempt never reach this class: they do not enter the tunnel (ADR 0005).
 */
public final class FirewallPolicy {

    public static final String REASON_APP_RULE = "App rule";
    public static final String REASON_METERED = "Metered";
    public static final String REASON_DEVICE_LOCKED = "Device locked";
    public static final String REASON_ERROR = "Firewall error";

    /** What the phone is doing right now. Asked only when a rule needs the answer. */
    public interface Environment {
        boolean metered();

        boolean deviceLocked();
    }

    private static final class Config {
        final Map<String, AppRule> rules;
        final boolean blockWhenLocked;
        final boolean blockMetered;

        Config(Map<String, AppRule> rules, boolean blockWhenLocked, boolean blockMetered) {
            this.rules = rules;
            this.blockWhenLocked = blockWhenLocked;
            this.blockMetered = blockMetered;
        }
    }

    private final Environment environment;
    private volatile Config config = new Config(Collections.emptyMap(), false, false);

    public FirewallPolicy(Environment environment) {
        this.environment = environment;
    }

    /** Replaces everything. {@code rules} may hold ALLOW entries; they are dropped. */
    public void configure(Map<String, AppRule> rules, boolean blockWhenLocked, boolean blockMetered) {
        Map<String, AppRule> kept = new HashMap<>();
        for (Map.Entry<String, AppRule> e : rules.entrySet()) {
            if (e.getValue() != AppRule.ALLOW) {
                kept.put(e.getKey(), e.getValue());
            }
        }
        config = new Config(Collections.unmodifiableMap(kept), blockWhenLocked, blockMetered);
    }

    /** True when a rule needs to know which app owns a flow; when false the owner lookup can be skipped. */
    public boolean needsOwner() {
        return !config.rules.isEmpty();
    }

    /** True when any rule at all is on. */
    public boolean isActive() {
        Config c = config;
        return !c.rules.isEmpty() || c.blockWhenLocked || c.blockMetered;
    }

    /**
     * @param appKey the app's key ({@code AppId.key()}); null when the owner is unknown or was not looked up.
     *               An unknown owner has no app rule, so only the universal rules can stop it.
     * @return the reason to refuse the flow (one of the REASON_ constants), or null to let it through
     */
    public String check(String appKey) {
        try {
            Config c = config;
            AppRule rule = appKey == null ? AppRule.ALLOW : c.rules.getOrDefault(appKey, AppRule.ALLOW);
            Boolean metered = null; // asked at most once, and only if a rule cares
            if (rule != AppRule.ALLOW) {
                if (rule == AppRule.BLOCK_BOTH) {
                    return REASON_APP_RULE;
                }
                metered = environment.metered();
                if (rule.blocks(metered)) {
                    return REASON_APP_RULE;
                }
            }
            if (c.blockMetered) {
                if (metered == null) {
                    metered = environment.metered();
                }
                if (metered) {
                    return REASON_METERED;
                }
            }
            if (c.blockWhenLocked && environment.deviceLocked()) {
                return REASON_DEVICE_LOCKED;
            }
            return null;
        } catch (RuntimeException e) {
            return REASON_ERROR;
        }
    }

    /** One line per rule, "key=RULE", ready for a string set in settings. ALLOW is not saved. */
    public static Set<String> encode(Map<String, AppRule> rules) {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, AppRule> e : rules.entrySet()) {
            if (e.getValue() != AppRule.ALLOW) {
                out.add(e.getKey() + "=" + e.getValue().name());
            }
        }
        return out;
    }

    /** The inverse of {@link #encode}. Lines without a known rule are skipped. */
    public static Map<String, AppRule> decode(Set<String> lines) {
        Map<String, AppRule> out = new HashMap<>();
        for (String line : lines) {
            int eq = line.lastIndexOf('=');
            if (eq <= 0) {
                continue;
            }
            AppRule rule = AppRule.parse(line.substring(eq + 1));
            if (rule != AppRule.ALLOW) {
                out.put(line.substring(0, eq), rule);
            }
        }
        return out;
    }
}
