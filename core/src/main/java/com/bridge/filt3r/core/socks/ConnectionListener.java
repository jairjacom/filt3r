package com.bridge.filt3r.core.socks;

import java.net.InetAddress;

/**
 * Told about every tunnelled TCP connection and what the bridge decided, before the answer goes back to
 * hev (a refusal) or as soon as a node or the direct fallback has accepted the connection (an allowed
 * flow). DNS to the tunnel's own address is not reported: the DNS log covers it. Runs on the connection's
 * thread, so it must return quickly; anything slow (such as asking Android who owns the socket) is the
 * listener's to hand off, except for a refused flow, whose socket hev closes right after the answer.
 */
public interface ConnectionListener {

    /** Refused because the destination is on the IP blocklist. */
    String REASON_IP_LIST = "IP list";

    /** Refused because the node has no IPv6 exit and IPv6 is off. */
    String REASON_IPV6_OFF = "IPv6";

    /** Refused because no node could be reached and the failure mode does not let it connect directly. */
    String REASON_NO_NODE = "No node";

    /**
     * @param client  the app's own socket as hev named it; null when hev named none
     * @param dest    where the app wanted to connect
     * @param blocked true when the bridge refused it
     * @param reason  why it was refused (one of the REASON_ constants); null when allowed
     */
    void onConnection(AppSocket client, InetAddress dest, int port, boolean blocked, String reason);
}
