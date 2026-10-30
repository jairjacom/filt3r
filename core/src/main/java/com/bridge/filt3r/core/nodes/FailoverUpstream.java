package com.bridge.filt3r.core.nodes;

import java.io.IOException;

import com.bridge.filt3r.core.dnscrypt.DnsCryptStamp;
import com.bridge.filt3r.core.dnscrypt.DnsCryptUpstream;
import com.bridge.filt3r.core.filter.DNSResolver;
import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;

/**
 * The DNS upstream through the current node. A query that works resets the node's failure count.
 * A query that fails after the node accepted the connection might be the node's fault (up but
 * dead) or the chosen resolver's, so the node is probed with the built-in test resolver first and
 * only counted as failed if the probe fails too. Every browsing session starts with DNS, so this
 * finds dead nodes quickly.
 */
public final class FailoverUpstream implements DNSResolver.Upstream {

    private static final long PROBE_OK_MS = 10_000; // a node that passed a probe is not probed again this soon

    private final NodeManager manager;
    private final DnsCryptUpstream upstream;
    private final NodeProbe probe;
    private ShadowsocksNode probedNode;   // guarded by this
    private long probedOkAt;              // guarded by this

    public FailoverUpstream(NodeManager manager, DnsCryptStamp stamp, FailoverDialer dialer, NodeProbe probe) {
        this.manager = manager;
        this.upstream = new DnsCryptUpstream(stamp, dialer);
        this.probe = probe;
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
            if (node != null && !nodeLooksFine(node)) {
                manager.recordFailure(node);
            }
            throw e;
        }
    }

    private boolean nodeLooksFine(ShadowsocksNode node) {
        synchronized (this) {
            if (node == probedNode && System.currentTimeMillis() - probedOkAt < PROBE_OK_MS) {
                return true;
            }
        }
        boolean works = probe.works(node);
        if (works) {
            synchronized (this) {
                probedNode = node;
                probedOkAt = System.currentTimeMillis();
            }
        }
        return works;
    }
}
