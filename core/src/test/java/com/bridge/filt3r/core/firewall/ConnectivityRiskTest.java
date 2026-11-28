package com.bridge.filt3r.core.firewall;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/** The "may break connectivity" tag: known names and the system user ID owners, nothing else. */
class ConnectivityRiskTest {

    @Test
    void playServicesCaptivePortalTelephonyAndSystemUidOwnersAreTagged() {
        assertTrue(ConnectivityRisk.mayBreakConnectivity(Set.of("com.google.android.gms")));
        assertTrue(ConnectivityRisk.mayBreakConnectivity(Set.of("com.google.android.gsf")));
        assertTrue(ConnectivityRisk.mayBreakConnectivity(Set.of("com.android.captiveportallogin")));
        assertTrue(ConnectivityRisk.mayBreakConnectivity(Set.of("com.android.phone")));
        assertTrue(ConnectivityRisk.mayBreakConnectivity(Set.of("android"))); // uid 1000
        assertTrue(ConnectivityRisk.mayBreakConnectivity(Set.of("com.android.shell"))); // uid 2000
    }

    @Test
    void unknownAppsGetNoTag() {
        assertFalse(ConnectivityRisk.mayBreakConnectivity(Set.of("com.android.calculator2")));
        assertFalse(ConnectivityRisk.mayBreakConnectivity(Set.of("org.example.notes")));
        assertFalse(ConnectivityRisk.mayBreakConnectivity(Set.of()));
    }

    @Test
    void aSharedUserIdGroupIsTaggedWhenAnyPackageInItIs() {
        // a rule reaches the whole user ID, so a harmless-looking sibling carries the tag too
        assertTrue(ConnectivityRisk.mayBreakConnectivity(
                Set.of("android", "com.android.settings.intelligence")));
    }

    @Test
    void anNameThatOnlyContainsAListedNameIsNotTagged() {
        assertFalse(ConnectivityRisk.mayBreakConnectivity(Set.of("com.google.android.gms.extra.app")));
    }
}
