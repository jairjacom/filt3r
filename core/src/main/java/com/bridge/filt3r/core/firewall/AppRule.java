package com.bridge.filt3r.core.firewall;

/**
 * What the firewall does with one app's traffic. "Wi-Fi" means the current network is not metered and
 * "mobile" means it is metered; RethinkDNS draws the line in the same place. An app with no saved rule is ALLOW.
 */
public enum AppRule {
    ALLOW,
    BLOCK_WIFI,
    BLOCK_MOBILE,
    BLOCK_BOTH;

    /** The rule for a saved name; ALLOW for null or a name this version does not know. */
    public static AppRule parse(String name) {
        for (AppRule r : values()) {
            if (r.name().equals(name)) {
                return r;
            }
        }
        return ALLOW;
    }

    /** True when this rule stops traffic on a network that is (or is not) metered. */
    public boolean blocks(boolean metered) {
        switch (this) {
            case BLOCK_BOTH:
                return true;
            case BLOCK_WIFI:
                return !metered;
            case BLOCK_MOBILE:
                return metered;
            default:
                return false;
        }
    }
}
