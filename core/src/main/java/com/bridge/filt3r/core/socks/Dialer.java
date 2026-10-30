package com.bridge.filt3r.core.socks;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;

/** Opens the outgoing TCP connection for one tunnelled connection. */
public interface Dialer {

    /** @return a connected socket; the caller closes it */
    Socket dial(InetAddress address, int port, int timeoutMs) throws IOException;
}
