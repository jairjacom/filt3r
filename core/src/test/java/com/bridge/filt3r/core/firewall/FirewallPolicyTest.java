package com.bridge.filt3r.core.firewall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The decision order from ADR 0005: app rule, then universal rules; first match wins; an error blocks. */
class FirewallPolicyTest {

    private boolean metered;
    private boolean locked;
    private int asked;
    private final FirewallPolicy policy = new FirewallPolicy(new FirewallPolicy.Environment() {
        @Override
        public boolean metered() {
            asked++;
            return metered;
        }

        @Override
        public boolean deviceLocked() {
            asked++;
            return locked;
        }
    });

    private static Map<String, AppRule> rules(String key, AppRule rule) {
        Map<String, AppRule> m = new HashMap<>();
        m.put(key, rule);
        return m;
    }

    @Test
    void withNoRulesEverythingPassesAndNothingIsAsked() {
        assertFalse(policy.isActive());
        assertFalse(policy.needsOwner());
        assertNull(policy.check("com.example.app"));
        assertNull(policy.check(null));
        assertEquals(0, asked);
    }

    @Test
    void blockWifiStopsAnAppOnAnUnmeteredNetworkOnly() {
        policy.configure(rules("a", AppRule.BLOCK_WIFI), false, false);
        metered = false;
        assertEquals(FirewallPolicy.REASON_APP_RULE, policy.check("a"));
        metered = true;
        assertNull(policy.check("a"));
    }

    @Test
    void blockMobileStopsAnAppOnAMeteredNetworkOnly() {
        policy.configure(rules("a", AppRule.BLOCK_MOBILE), false, false);
        metered = true;
        assertEquals(FirewallPolicy.REASON_APP_RULE, policy.check("a"));
        metered = false;
        assertNull(policy.check("a"));
    }

    @Test
    void blockBothStopsAnAppWithoutAskingTheNetwork() {
        policy.configure(rules("a", AppRule.BLOCK_BOTH), false, false);
        assertEquals(FirewallPolicy.REASON_APP_RULE, policy.check("a"));
        assertEquals(0, asked);
    }

    @Test
    void anAppRuleOnlyAffectsThatApp() {
        policy.configure(rules("a", AppRule.BLOCK_BOTH), false, false);
        assertNull(policy.check("b"));
    }

    @Test
    void anUnknownOwnerHasNoAppRuleButUniversalRulesStillApply() {
        policy.configure(rules("a", AppRule.BLOCK_BOTH), true, false);
        locked = false;
        assertNull(policy.check(null));
        locked = true;
        assertEquals(FirewallPolicy.REASON_DEVICE_LOCKED, policy.check(null));
    }

    @Test
    void theAppRuleIsReportedBeforeAUniversalRule() {
        policy.configure(rules("a", AppRule.BLOCK_BOTH), true, true);
        metered = true;
        locked = true;
        assertEquals(FirewallPolicy.REASON_APP_RULE, policy.check("a"));
    }

    @Test
    void blockMeteredIsReportedBeforeBlockWhenLocked() {
        policy.configure(new HashMap<>(), true, true);
        metered = true;
        locked = true;
        assertEquals(FirewallPolicy.REASON_METERED, policy.check("a"));
        metered = false;
        assertEquals(FirewallPolicy.REASON_DEVICE_LOCKED, policy.check("a"));
        locked = false;
        assertNull(policy.check("a"));
    }

    @Test
    void allowEntriesAreDropped() {
        policy.configure(rules("a", AppRule.ALLOW), false, false);
        assertFalse(policy.isActive());
    }

    @Test
    void universalRulesAloneDoNotNeedTheOwner() {
        policy.configure(new HashMap<>(), true, false);
        assertTrue(policy.isActive());
        assertFalse(policy.needsOwner());
    }

    @Test
    void anErrorWhileLookingAtThePhoneBlocks() {
        FirewallPolicy broken = new FirewallPolicy(new FirewallPolicy.Environment() {
            @Override
            public boolean metered() {
                throw new IllegalStateException("no network service");
            }

            @Override
            public boolean deviceLocked() {
                return false;
            }
        });
        broken.configure(new HashMap<>(), false, true);
        assertEquals(FirewallPolicy.REASON_ERROR, broken.check("a"));
    }

    @Test
    void savedRulesComeBackAsTheyWentIn() {
        Map<String, AppRule> in = new HashMap<>();
        in.put("com.example.app", AppRule.BLOCK_WIFI);
        in.put("system:android.uid.system:1000", AppRule.BLOCK_BOTH);
        in.put("uid:10123", AppRule.ALLOW);
        Map<String, AppRule> out = FirewallPolicy.decode(FirewallPolicy.encode(in));
        assertEquals(2, out.size());
        assertEquals(AppRule.BLOCK_WIFI, out.get("com.example.app"));
        assertEquals(AppRule.BLOCK_BOTH, out.get("system:android.uid.system:1000"));
    }

    @Test
    void decodeSkipsLinesItDoesNotUnderstand() {
        Set<String> lines = new HashSet<>();
        lines.add("=BLOCK_BOTH");
        lines.add("no-equals-sign");
        lines.add("a=SOMETHING_NEW");
        lines.add("b=BLOCK_MOBILE");
        Map<String, AppRule> out = FirewallPolicy.decode(lines);
        assertEquals(1, out.size());
        assertEquals(AppRule.BLOCK_MOBILE, out.get("b"));
    }
}
