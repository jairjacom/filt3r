package com.bridge.filt3r.core.nodes;

import java.io.IOException;

import com.bridge.filt3r.core.dnscrypt.DnsCryptStamp;
import com.bridge.filt3r.core.dnscrypt.DnsCryptUpstream;
import com.bridge.filt3r.core.filter.DNSResolver;
import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;
import com.bridge.filt3r.core.socks.Dialer;

/**
 * The DNS upstream through the current node. A query that works resets the node's failure count;
 * one that fails after the node accepted the connection (a node that is up but dead) counts against it.
 * Every browsing session starts with DNS, so this finds dead nodes quickly.
 */
public final class FailoverUpstream implements DNSResolver.Upstream {

    private final NodeManager manager;
    private final DnsCryptUpstream upstream;

    public FailoverUpstream(NodeManager manager, DnsCryptStamp stamp, FailoverDialer dialer) {
        this.manager = manager;
        this.upstream = new DnsCryptUpstream(stamp, dialer);
    }

    @Override
    public byte[] query(byte[] request) throws IOException {
        ShadowsocksNode node = manager.current();
        try {
            byte[] answer = upstream.query(request);
            if (node != null) {
                manager.recordSuccess(node);
            }
            return answer;
        } catch (NodeDialException e) {
            throw e; // already counted by the dialer
        } catch (IOException e) {
            if (node != null) {
                manager.recordFailure(node);
            }
            throw e;
        }
    }
}
