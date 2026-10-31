package com.bridge.filt3r.core.nodes;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.bridge.filt3r.core.dns.DnsWire;
import com.bridge.filt3r.core.dnscrypt.DnsCryptStamp;
import com.bridge.filt3r.core.dnscrypt.DnsCryptUpstream;
import com.bridge.filt3r.core.shadowsocks.ShadowsocksDialer;
import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;
import com.bridge.filt3r.core.socks.Dialer;

/**
 * Tests nodes with one DNSCrypt-over-TCP query (certificate fetch included) sent through each node,
 * and checks IPv6 egress with the same query sent to the resolver's IPv6 address.
 */
public final class NodeTester implements NodeProbe {

    private static final String TEST_NAME = "example.com";
    private static final long ROUND_TIMEOUT_S = 40;

    private final Dialer direct;
    private final DnsCryptStamp stamp;
    private final DnsCryptStamp ipv6Stamp;

    /**
     * @param direct opens plain TCP connections to the nodes, outside the VPN
     * @param stamp the resolver used to time nodes (IPv4)
     * @param ipv6Stamp the same kind of resolver at an IPv6 address; reaching it proves IPv6 egress
     */
    public NodeTester(Dialer direct, DnsCryptStamp stamp, DnsCryptStamp ipv6Stamp) {
        this.direct = direct;
        this.stamp = stamp;
        this.ipv6Stamp = ipv6Stamp;
    }

    /**
     * Asks the IPv6 resolver for a name through the node. Shadowsocks gives no connect reply, so only
     * a real answer proves the node can reach IPv6 destinations. A failure also happens if that one
     * resolver is down, in which case IPv6 stays off (apps use IPv4).
     */
    public boolean hasIpv6(ShadowsocksNode node) {
        try {
            query(node, ipv6Stamp);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** @return milliseconds until the answer arrived; @throws IOException if the node does not work */
    public long test(ShadowsocksNode node) throws IOException {
        long start = System.nanoTime();
        query(node, stamp);
        return (System.nanoTime() - start) / 1_000_000;
    }

    private void query(ShadowsocksNode node, DnsCryptStamp resolver) throws IOException {
        DnsCryptUpstream upstream = new DnsCryptUpstream(resolver, new ShadowsocksDialer(node, direct));
        byte[] answer = upstream.query(DnsWire.query(TEST_NAME, DnsWire.TYPE_A));
        DnsWire.answers(answer, DnsWire.TYPE_A); // throws if the resolver reported an error
    }

    @Override
    public boolean works(ShadowsocksNode node) {
        try {
            test(node);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Tests the nodes in parallel and records each result in the manager. Blocks until done or timed out. */
    public void testAndRecord(NodeManager manager, List<ShadowsocksNode> nodes, int threads) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(threads, nodes.size())));
        for (ShadowsocksNode n : nodes) {
            pool.execute(() -> {
                try {
                    manager.recordTest(n, test(n));
                } catch (IOException | RuntimeException e) {
                    manager.recordFailure(n);
                }
            });
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(ROUND_TIMEOUT_S, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
