package com.bridge.filt3r.core.firewall;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which Exempt apps stay out of the tunnel (ADR 0006). Android leaves a whole user ID out when one of its packages is
 * excluded, so everything here works on user ID groups. An Exempt app with a box ticked is a Gated exempt app: it enters
 * the tunnel so the rule can reach it; the rest stay outside, exactly as before.
 */
public final class ExemptTunnel {

    private ExemptTunnel() {
    }

    /**
     * The Exempt packages that stay outside the tunnel: those whose user ID group has no box ticked on any package.
     *
     * @param groupOfExempt each Exempt package with the packages that share its user ID (itself included)
     * @param rules the saved rules by package; empty ones count as no rule
     */
    public static Set<String> outside(Map<String, Set<String>> groupOfExempt, Map<String, AppRule> rules) {
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, Set<String>> e : groupOfExempt.entrySet()) {
            if (!anyRule(e.getValue(), rules)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /**
     * True when ticking a box for these groups would move an Exempt app into the tunnel: some group has an Exempt
     * package and no box ticked yet. The user is told first, because a gated app loses non-DNS UDP (calls, QUIC).
     */
    public static boolean wouldEnterTunnel(Collection<Set<String>> groups, Set<String> exempt, Map<String, AppRule> rules) {
        for (Set<String> group : groups) {
            if (!anyRule(group, rules) && intersects(group, exempt)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anyRule(Set<String> group, Map<String, AppRule> rules) {
        for (String key : group) {
            AppRule r = rules.get(key);
            if (r != null && !r.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static boolean intersects(Set<String> group, Set<String> exempt) {
        for (String key : group) {
            if (exempt.contains(key)) {
                return true;
            }
        }
        return false;
    }
}
