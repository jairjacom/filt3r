package com.bridge.filt3r.core.socks;

import java.io.IOException;
import java.net.InetAddress;

/** Opens the outgoing connection for one tunnelled TCP connection. */
public interface Dialer {

    /** @return a connected link; the caller closes it */
    Link dial(InetAddress address, int port, int timeoutMs) throws IOException;

    /**
     * Names the exit the next connection leaves from (the node). A client that identifies itself to
     * the far end with a key must start a new key when this changes, or the far end can link the
     * two exits. The default is one fixed exit.
     */
    default String exitId() {
        return "";
    }
}
