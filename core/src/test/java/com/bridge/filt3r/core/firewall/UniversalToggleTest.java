package com.bridge.filt3r.core.firewall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.bridge.filt3r.core.firewall.UniversalToggle.Box;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The universal toggles from ADR 0006: on only when every app has the box; on ticks all, off clears all. */
class UniversalToggleTest {

    private static final AppRule WIFI = AppRule.of(true, false, false);
    private static final AppRule MOBILE = AppRule.of(false, true, false);
    private static final AppRule WIFI_AND_LOCKED = AppRule.of(true, false, true);

    private static final Set<String> A = Set.of("a");
    private static final Set<String> B = Set.of("b");
    private static final Set<String> SHARED = Set.of("s1", "s2");

    @Test
    void readsOnOnlyWhenEveryAppHasTheBox() {
        Map<String, AppRule> rules = new HashMap<>(Map.of("a", WIFI, "b", WIFI));
        assertTrue(UniversalToggle.isOn(Box.WIFI, List.of(A, B), rules));

        rules.remove("b");
        assertFalse(UniversalToggle.isOn(Box.WIFI, List.of(A, B), rules));

        rules.put("b", MOBILE); // a rule, but not this box
        assertFalse(UniversalToggle.isOn(Box.WIFI, List.of(A, B), rules));
    }

    @Test
    void switchingOnTicksTheBoxForEveryAppAndKeepsTheOtherBoxes() {
        Map<String, AppRule> rules = Map.of("a", MOBILE, "other", WIFI);

        Map<String, AppRule> next = UniversalToggle.switchTo(Box.WIFI, true, List.of(A, B), rules);

        assertEquals(AppRule.of(true, true, false), next.get("a"));
        assertEquals(WIFI, next.get("b"));
        assertEquals(WIFI, next.get("other")); // not in the tab: untouched
        assertTrue(UniversalToggle.isOn(Box.WIFI, List.of(A, B), next));
    }

    @Test
    void switchingOffClearsTheBoxForEveryAppAndDropsEmptyRules() {
        Map<String, AppRule> rules = Map.of("a", WIFI, "b", WIFI_AND_LOCKED, "other", WIFI);

        Map<String, AppRule> next = UniversalToggle.switchTo(Box.WIFI, false, List.of(A, B), rules);

        assertFalse(next.containsKey("a")); // no box left: no saved rule
        assertEquals(AppRule.of(false, false, true), next.get("b"));
        assertEquals(WIFI, next.get("other"));
        assertFalse(UniversalToggle.isOn(Box.WIFI, List.of(A, B), next));
    }

    @Test
    void aSharedUserIdGroupChangesAsOneAndReadsOnOnlyWhenEveryPackageHasTheBox() {
        Map<String, AppRule> half = Map.of("s1", WIFI); // s2 has none

        assertFalse(UniversalToggle.isOn(Box.WIFI, List.of(SHARED), half));

        Map<String, AppRule> next = UniversalToggle.switchTo(Box.WIFI, true, List.of(SHARED, SHARED), half);
        assertEquals(WIFI, next.get("s1"));
        assertEquals(WIFI, next.get("s2"));
        assertTrue(UniversalToggle.isOn(Box.WIFI, List.of(SHARED, SHARED), next));
    }

    @Test
    void anEmptyTabIsOffAndSwitchingItChangesNothing() {
        Map<String, AppRule> rules = Map.of("other", WIFI);

        assertFalse(UniversalToggle.isOn(Box.WIFI, List.of(), rules));
        assertEquals(rules, UniversalToggle.switchTo(Box.WIFI, true, List.of(), rules));
    }

    @Test
    void theInputRulesAreNotChanged() {
        Map<String, AppRule> rules = new HashMap<>(Map.of("a", WIFI));

        UniversalToggle.switchTo(Box.MOBILE, true, List.of(A, B), rules);

        assertEquals(Map.of("a", WIFI), rules);
    }
}
