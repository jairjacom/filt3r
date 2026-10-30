package com.bridge.filt3r.core.socks;

/** What the bridge does when the main dialer cannot reach a destination. */
public enum FailureMode {
    /** Refuse the connection. The real IP is never exposed. Default. */
    FAIL_CLOSED,
    /** Connect directly instead. Traffic keeps flowing but the real IP is exposed. */
    FAIL_OPEN
}
