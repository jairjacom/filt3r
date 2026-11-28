package com.bridge.filt3r.core.firewall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** An Exempt app enters the tunnel with its first box and leaves with its last; a shared user ID moves as one. */
class ExemptTunnelTest {

    private static final AppRule WIFI = AppRule.of(true, false, false);
    private static final Set<String> CALLS_GROUP = Set.of("calls", "calls.helper");
    private final Map<String, Set<String>> exempt = Map.of("calls", CALLS_GROUP, "calls.helper", CALLS_GROUP,
            "mail", Set.of("mail"));

    @Test
    void noBoxMeansOutsideOneBoxMeansInsideAndClearingTheLastBoxMeansOutsideAgain() {
        Map<String, AppRule> rules = new HashMap<>();
        assertEquals(Set.of("calls", "calls.helper", "mail"), ExemptTunnel.outside(exempt, rules));

        rules.put("mail", WIFI);
        assertEquals(Set.of("calls", "calls.helper"), ExemptTunnel.outside(exempt, rules));

        rules.remove("mail");
        assertEquals(Set.of("calls", "calls.helper", "mail"), ExemptTunnel.outside(exempt, rules));
    }

    @Test
    void aBoxOnAnyPackageOfASharedUserIdMovesTheWholeGroupInsideAndAnEmptyRuleDoesNot() {
        Map<String, AppRule> rules = new HashMap<>();
        rules.put("calls.helper", WIFI);
        assertEquals(Set.of("mail"), ExemptTunnel.outside(exempt, rules));

        rules.put("calls.helper", AppRule.NONE);
        assertEquals(Set.of("calls", "calls.helper", "mail"), ExemptTunnel.outside(exempt, rules));
    }

    @Test
    void aSiblingThatIsNotExemptButHasABoxStillPullsTheExemptPackageInside() {
        // the group comes from Android, so a non-Exempt package of the same user ID counts
        Map<String, Set<String>> one = Map.of("calls", Set.of("calls", "sibling"));
        assertTrue(ExemptTunnel.outside(one, Map.of("sibling", WIFI)).isEmpty());
    }

    @Test
    void ticksWarnOnlyWhenAnExemptAppWithNoBoxWouldEnterTheTunnel() {
        Set<String> exemptKeys = Set.of("calls");
        Map<String, AppRule> rules = new HashMap<>();
        List<Set<String>> tab = List.of(Set.of("plain"), CALLS_GROUP);

        assertTrue(ExemptTunnel.wouldEnterTunnel(tab, exemptKeys, rules));
        assertFalse(ExemptTunnel.wouldEnterTunnel(List.of(Set.of("plain")), exemptKeys, rules));

        rules.put("calls", WIFI); // already inside: a second box is no news
        assertFalse(ExemptTunnel.wouldEnterTunnel(tab, exemptKeys, rules));
    }
}
