package com.bridge.filt3r.core.shadowsocks;

import java.io.IOException;
import java.net.InetAddress;

import com.bridge.filt3r.core.socks.Dialer;
import com.bridge.filt3r.core.socks.Link;

/** Reaches destinations through one Shadowsocks server. */
public final class ShadowsocksDialer implements Dialer {

    private final AeadSuite suite;
    private final byte[] masterKey;
    private final InetAddress serverAddress;
    private final int serverPort;
    private final Dialer transport;

    /** @param transport opens the plain TCP connection to the server (outside the VPN) */
    public ShadowsocksDialer(ShadowsocksNode node, Dialer transport) throws IOException {
        this.suite = node.suite;
        this.masterKey = node.suite.masterKey(node.password);
        this.serverAddress = node.address();
        this.serverPort = node.port;
        this.transport = transport;
    }

    @Override
    public Link dial(InetAddress address, int port, int timeoutMs) throws IOException {
        Link raw = transport.dial(serverAddress, serverPort, timeoutMs);
        return new ShadowsocksLink(raw, suite, masterKey, address, port);
    }
}
