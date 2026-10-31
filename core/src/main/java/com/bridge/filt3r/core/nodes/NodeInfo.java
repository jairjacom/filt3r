package com.bridge.filt3r.core.nodes;

import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;

/**
 * A read-only snapshot of one node for the Node list screen. It holds the node itself, so never
 * log or print its address or password.
 */
public final class NodeInfo {

    public enum State {
        /** Answered its last test(s); has a time. */
        WORKING,
        /** Never tested, or only failed so far without reaching the blacklist. */
        UNTESTED,
        /** Failed {@value NodeManager#FAILURES_TO_BLACKLIST} times in a row; skipped until the next subscription update. */
        BLACKLISTED
    }

    public final ShadowsocksNode node;
    public final State state;
    /** Median of the recent test times in ms, or -1 if the node has never answered. */
    public final long medianMs;
    /** Failures in a row since the last success. */
    public final int failures;
    /** True for the node carrying traffic now. */
    public final boolean current;
    /** True or false once the node was probed for IPv6 egress; null if it has not been probed. */
    public final Boolean ipv6;
    /** True for the node the user pinned. */
    public final boolean pinned;
    /** Download speed in bytes per second from the last speed test, or -1 if never measured. */
    public final long speedBps;

    NodeInfo(ShadowsocksNode node, State state, long medianMs, int failures, boolean current, Boolean ipv6,
            boolean pinned, long speedBps) {
        this.node = node;
        this.state = state;
        this.medianMs = medianMs;
        this.failures = failures;
        this.current = current;
        this.ipv6 = ipv6;
        this.pinned = pinned;
        this.speedBps = speedBps;
    }
}
