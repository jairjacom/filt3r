package com.bridge.filt3r.core.firewall;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The firewall's decision, kept free of Android so it can be tested on the JVM. One app rule per app (the boxes
 * the user ticked); a flow with no known owner has no rule and passes (ADR 0004, ADR 0006). An unexpected error
 * blocks. Apps that are Exempt and have no box ticked never reach this class: they do not enter the tunnel.
 */
public final class FirewallPolicy {

    public static final String REASON_APP_RULE = "App rule";
    public static final String REASON_DEVICE_LOCKED = "Device locked";
    public static final String REASON_ERROR = "Firewall error";

    /** What the phone is doing right now. Asked only when a rule needs the answer. */
    public interface Environment {
        boolean metered();

        boolean deviceLocked();
    }

    private final Environment environment;
    private volatile Map<String, AppRule> rules = Collections.emptyMap();

    public FirewallPolicy(Environment environment) {
        this.environment = environment;
    }

    /** Replaces everything. {@code rules} may hold empty entries; they are dropped. */
    public void configure(Map<String, AppRule> rules) {
        Map<String, AppRule> kept = new HashMap<>();
        for (Map.Entry<String, AppRule> e : rules.entrySet()) {
            if (!e.getValue().isEmpty()) {
                kept.put(e.getKey(), e.getValue());
            }
        }
        this.rules = Collections.unmodifiableMap(kept);
    }

    /** True when a rule needs to know which app owns a flow; when false the owner lookup can be skipped. */
    public boolean needsOwner() {
        return !rules.isEmpty();
    }

    /**
     * @param appKey the app's key ({@code AppId.key()}); null when the owner is unknown or was not looked up.
     *               An unknown owner has no rule, so it always passes.
     * @return the reason to refuse the flow (one of the REASON_ constants), or null to let it through
     */
    public String check(String appKey) {
        try {
            AppRule rule = appKey == null ? null : rules.get(appKey);
            if (rule == null) {
                return null;
            }
            if (rule.wifi() && rule.mobile()) {
                return REASON_APP_RULE; // blocked on every network: no need to ask which one this is
            }
            if ((rule.wifi() || rule.mobile()) && rule.blocks(environment.metered())) {
                return REASON_APP_RULE;
            }
            if (rule.locked() && environment.deviceLocked()) {
                return REASON_DEVICE_LOCKED;
            }
            return null;
        } catch (RuntimeException e) {
            return REASON_ERROR;
        }
    }

    /** One line per rule, "key=WIFI,LOCKED", ready for a string set in settings. Empty rules are not saved. */
    public static Set<String> encode(Map<String, AppRule> rules) {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, AppRule> e : rules.entrySet()) {
            if (!e.getValue().isEmpty()) {
                out.add(e.getKey() + "=" + e.getValue().encode());
            }
        }
        return out;
    }

    /** The inverse of {@link #encode}; also reads the lines the first firewall saved. Lines without a known rule are skipped. */
    public static Map<String, AppRule> decode(Set<String> lines) {
        Map<String, AppRule> out = new HashMap<>();
        for (String line : lines) {
            int eq = line.lastIndexOf('=');
            if (eq <= 0) {
                continue;
            }
            AppRule rule = AppRule.parse(line.substring(eq + 1));
            if (!rule.isEmpty()) {
                out.put(line.substring(0, eq), rule);
            }
        }
        return out;
    }
}
