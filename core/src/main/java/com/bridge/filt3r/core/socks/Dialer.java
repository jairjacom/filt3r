package com.bridge.filt3r.core.socks;

import java.io.IOException;
import java.net.InetAddress;

/** Opens the outgoing connection for one tunnelled TCP connection. */
public interface Dialer {

    /** @return a connected link; the caller closes it */
    Link dial(InetAddress address, int port, int timeoutMs) throws IOException;
}
