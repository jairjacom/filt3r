package com.bridge.filt3r.core.socks;

import java.net.InetAddress;

/**
 * Asked before the bridge dials: may this app's connection go on? Runs on the connection's own thread, and
 * the app's socket is still open then, so it is the moment Android can still name the owner.
 */
public interface ConnectionGate {

    /**
     * @param client the app's own socket as hev named it; null when hev named none
     * @return why the connection is refused (one of the REASON_ constants), or null to let it through
     */
    String refuse(AppSocket client, InetAddress dest, int port);
}
