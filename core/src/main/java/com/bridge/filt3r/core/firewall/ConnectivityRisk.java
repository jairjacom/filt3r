package com.bridge.filt3r.core.firewall;

import java.util.Set;

/**
 * The short list of system packages whose block is known to break connectivity: Play services and its
 * framework, captive-portal login and network stack, telephony and carrier services, and the packages that
 * own the system user IDs (1000 is "android", 2000 is the shell; user ID 0 has no package, so it never shows).
 * It answers only "may break connectivity" or nothing. An app that is not on the list is not known to be
 * safe: the list is short, and nothing here ever says an app can be blocked without harm.
 */
public final class ConnectivityRisk {

    private static final Set<String> RISKY = Set.of(
            "android",
            "com.android.shell",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.android.captiveportallogin",
            "com.google.android.captiveportallogin",
            "com.android.networkstack",
            "com.google.android.networkstack",
            "com.android.phone",
            "com.android.providers.telephony",
            "com.android.carrierconfig",
            "com.android.ons",
            "com.android.server.telecom",
            "com.google.android.ims",
            "com.google.android.carriersetup");

    private ConnectivityRisk() {
    }

    /**
     * True when any package of the app's user ID is on the list. A rule for an app reaches its whole user
     * ID, so the whole group counts.
     */
    public static boolean mayBreakConnectivity(Set<String> group) {
        for (String pkg : group) {
            if (RISKY.contains(pkg)) {
                return true;
            }
        }
        return false;
    }
}
