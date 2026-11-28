package com.bridge.filt3r.core.firewall;

/**
 * What the firewall blocks for one app: any mix of "Wi-Fi", "mobile" and "device locked". "Wi-Fi" means the
 * current network is not metered and "mobile" means it is metered; RethinkDNS draws the line in the same place.
 * An app with nothing ticked is allowed. Immutable.
 */
public final class AppRule {

    /** Nothing ticked: the app is allowed. */
    public static final AppRule NONE = new AppRule(false, false, false);

    private static final String WIFI = "WIFI";
    private static final String MOBILE = "MOBILE";
    private static final String LOCKED = "LOCKED";

    private final boolean wifi;
    private final boolean mobile;
    private final boolean locked;

    private AppRule(boolean wifi, boolean mobile, boolean locked) {
        this.wifi = wifi;
        this.mobile = mobile;
        this.locked = locked;
    }

    public static AppRule of(boolean wifi, boolean mobile, boolean locked) {
        return wifi || mobile || locked ? new AppRule(wifi, mobile, locked) : NONE;
    }

    public boolean wifi() {
        return wifi;
    }

    public boolean mobile() {
        return mobile;
    }

    public boolean locked() {
        return locked;
    }

    public boolean isEmpty() {
        return !wifi && !mobile && !locked;
    }

    public AppRule withWifi(boolean on) {
        return of(on, mobile, locked);
    }

    public AppRule withMobile(boolean on) {
        return of(wifi, on, locked);
    }

    public AppRule withLocked(boolean on) {
        return of(wifi, mobile, on);
    }

    /** True when this rule stops traffic on a network that is (or is not) metered. */
    public boolean blocks(boolean metered) {
        return metered ? mobile : wifi;
    }

    /**
     * The saved text: the ticked boxes, comma-separated ("WIFI,LOCKED"). Empty for {@link #NONE}, which is
     * never saved.
     */
    public String encode() {
        StringBuilder sb = new StringBuilder();
        if (wifi) {
            sb.append(WIFI);
        }
        if (mobile) {
            sb.append(sb.length() == 0 ? "" : ",").append(MOBILE);
        }
        if (locked) {
            sb.append(sb.length() == 0 ? "" : ",").append(LOCKED);
        }
        return sb.toString();
    }

    /**
     * The rule for a saved text. Also reads the four names the first firewall saved (BLOCK_WIFI, BLOCK_MOBILE,
     * BLOCK_BOTH, ALLOW). Words it does not know are skipped, so null or garbage gives {@link #NONE}.
     */
    public static AppRule parse(String text) {
        if (text == null) {
            return NONE;
        }
        boolean wifi = false;
        boolean mobile = false;
        boolean locked = false;
        for (String word : text.split(",")) {
            switch (word.trim()) {
                case WIFI:
                case "BLOCK_WIFI":
                    wifi = true;
                    break;
                case MOBILE:
                case "BLOCK_MOBILE":
                    mobile = true;
                    break;
                case LOCKED:
                    locked = true;
                    break;
                case "BLOCK_BOTH":
                    wifi = true;
                    mobile = true;
                    break;
                default:
                    break;
            }
        }
        return of(wifi, mobile, locked);
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof AppRule)) {
            return false;
        }
        AppRule r = (AppRule) o;
        return wifi == r.wifi && mobile == r.mobile && locked == r.locked;
    }

    @Override
    public int hashCode() {
        return (wifi ? 1 : 0) | (mobile ? 2 : 0) | (locked ? 4 : 0);
    }

    @Override
    public String toString() {
        return isEmpty() ? "NONE" : encode();
    }
}
