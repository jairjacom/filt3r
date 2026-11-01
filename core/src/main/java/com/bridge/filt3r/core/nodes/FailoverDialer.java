package com.bridge.filt3r.core.nodes;

import java.io.IOException;
import java.net.InetAddress;

import com.bridge.filt3r.core.shadowsocks.ShadowsocksDialer;
import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;
import com.bridge.filt3r.core.socks.Dialer;
import com.bridge.filt3r.core.socks.Link;
import com.bridge.filt3r.core.socks.NoNodeException;

/** Dials through the manager's current node. A failed connection to the node counts against it. */
public final class FailoverDialer implements Dialer {

    private final NodeManager manager;
    private final Dialer direct;
    private ShadowsocksNode cachedNode;     // guarded by this
    private ShadowsocksDialer cachedDialer; // guarded by this

    public FailoverDialer(NodeManager manager, Dialer direct) {
        this.manager = manager;
        this.direct = direct;
    }

    @Override
    public Link dial(InetAddress address, int port, int timeoutMs) throws IOException {
        ShadowsocksNode node = manager.current();
        if (node == null) {
            throw new NoNodeException();
        }
        try {
            return dialerFor(node).dial(address, port, timeoutMs);
        } catch (IOException e) {
            manager.recordFailure(node);
            throw new NodeDialException("node unreachable", e);
        }
    }

    private synchronized ShadowsocksDialer dialerFor(ShadowsocksNode node) throws IOException {
        if (node != cachedNode) {
            cachedDialer = new ShadowsocksDialer(node, direct);
            cachedNode = node;
        }
        return cachedDialer;
    }
}
