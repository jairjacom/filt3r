package com.bridge.filt3r.core.firewall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The decision from ADR 0006: one rule per app made of Wi-Fi, Mobile and Device locked boxes; an error blocks. */
class FirewallPolicyTest {

    private static final AppRule WIFI = AppRule.of(true, false, false);
    private static final AppRule MOBILE = AppRule.of(false, true, false);
    private static final AppRule LOCKED = AppRule.of(false, false, true);
    private static final AppRule WIFI_AND_MOBILE = AppRule.of(true, true, false);

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
        assertFalse(policy.needsOwner());
        metered = true;
        locked = true;
        assertNull(policy.check("a"));
        assertNull(policy.check(null));
        assertEquals(0, asked);
    }

    @Test
    void wifiStopsAnAppOnAnUnmeteredNetworkOnly() {
        policy.configure(rules("a", WIFI));
        metered = false;
        assertEquals(FirewallPolicy.REASON_APP_RULE, policy.check("a"));
        metered = true;
        assertNull(policy.check("a"));
    }

    @Test
    void mobileStopsAnAppOnAMeteredNetworkOnly() {
        policy.configure(rules("a", MOBILE));
        metered = true;
        assertEquals(FirewallPolicy.REASON_APP_RULE, policy.check("a"));
        metered = false;
        assertNull(policy.check("a"));
    }

    @Test
    void wifiAndMobileTogetherStopAnAppEverywhereWithoutAskingTheNetwork() {
        policy.configure(rules("a", WIFI_AND_MOBILE));
        assertEquals(FirewallPolicy.REASON_APP_RULE, policy.check("a"));
        assertEquals(0, asked);
    }

    @Test
    void deviceLockedStopsAnAppOnlyWhileTheDeviceIsLocked() {
        policy.configure(rules("a", LOCKED));
        locked = true;
        assertEquals(FirewallPolicy.REASON_DEVICE_LOCKED, policy.check("a"));
        locked = false;
        assertNull(policy.check("a"));
    }

    @Test
    void lockedDoesNotAskWhichNetworkItIsAndMobileDoesNotAskIfLocked() {
        policy.configure(rules("a", LOCKED));
        policy.check("a");
        assertEquals(1, asked); // only deviceLocked

        asked = 0;
        policy.configure(rules("a", MOBILE));
        policy.check("a");
        assertEquals(1, asked); // only metered
    }

    @Test
    void theBoxesCombine() {
        policy.configure(rules("a", AppRule.of(true, false, true)));
        metered = true;
        locked = false;
        assertNull(policy.check("a")); // mobile and unlocked: neither box applies
        locked = true;
        assertEquals(FirewallPolicy.REASON_DEVICE_LOCKED, policy.check("a"));
        metered = false;
        locked = false;
        assertEquals(FirewallPolicy.REASON_APP_RULE, policy.check("a"));
    }

    @Test
    void aNetworkBoxIsReportedBeforeDeviceLocked() {
        policy.configure(rules("a", AppRule.of(true, false, true)));
        metered = false;
        locked = true;
        assertEquals(FirewallPolicy.REASON_APP_RULE, policy.check("a"));
    }

    @Test
    void aRuleOnlyAffectsThatApp() {
        policy.configure(rules("a", WIFI_AND_MOBILE));
        assertNull(policy.check("b"));
    }

    @Test
    void anUnknownOwnerAlwaysPasses() {
        policy.configure(rules("a", AppRule.of(true, true, true)));
        locked = true;
        assertNull(policy.check(null));
    }

    @Test
    void emptyRulesAreDropped() {
        policy.configure(rules("a", AppRule.NONE));
        assertFalse(policy.needsOwner());
        assertNull(policy.check("a"));
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
        broken.configure(rules("a", WIFI));
        assertEquals(FirewallPolicy.REASON_ERROR, broken.check("a"));
    }

    @Test
    void savedRulesComeBackAsTheyWentIn() {
        Map<String, AppRule> in = new HashMap<>();
        in.put("com.example.app", AppRule.of(true, false, true));
        in.put("system:android.uid.system:1000", AppRule.of(true, true, true));
        in.put("uid:10123", AppRule.NONE);
        Map<String, AppRule> out = FirewallPolicy.decode(FirewallPolicy.encode(in));
        assertEquals(2, out.size());
        assertEquals(AppRule.of(true, false, true), out.get("com.example.app"));
        assertEquals(AppRule.of(true, true, true), out.get("system:android.uid.system:1000"));
    }

    @Test
    void rulesSavedByTheFirstFirewallStillMeanTheSame() {
        Set<String> lines = new HashSet<>();
        lines.add("a=BLOCK_WIFI");
        lines.add("b=BLOCK_MOBILE");
        lines.add("c=BLOCK_BOTH");
        lines.add("d=ALLOW");
        Map<String, AppRule> out = FirewallPolicy.decode(lines);
        assertEquals(3, out.size());
        assertEquals(WIFI, out.get("a"));
        assertEquals(MOBILE, out.get("b"));
        assertEquals(WIFI_AND_MOBILE, out.get("c"));
        assertFalse(out.containsKey("d"));
    }

    @Test
    void decodeSkipsLinesItDoesNotUnderstand() {
        Set<String> lines = new HashSet<>();
        lines.add("=WIFI");
        lines.add("no-equals-sign");
        lines.add("a=SOMETHING_NEW");
        lines.add("b=MOBILE,SOMETHING_NEW");
        Map<String, AppRule> out = FirewallPolicy.decode(lines);
        assertEquals(1, out.size());
        assertEquals(MOBILE, out.get("b"));
    }
}
