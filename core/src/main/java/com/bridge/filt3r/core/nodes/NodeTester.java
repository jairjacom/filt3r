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

/** Tests nodes with one DNSCrypt-over-TCP query (certificate fetch included) sent through each node. */
public final class NodeTester {

    private static final String TEST_NAME = "example.com";
    private static final long ROUND_TIMEOUT_S = 40;

    private final Dialer direct;
    private final DnsCryptStamp stamp;

    /** @param direct opens plain TCP connections to the nodes, outside the VPN */
    public NodeTester(Dialer direct, DnsCryptStamp stamp) {
        this.direct = direct;
        this.stamp = stamp;
    }

    /** @return milliseconds until the answer arrived; @throws IOException if the node does not work */
    public long test(ShadowsocksNode node) throws IOException {
        long start = System.nanoTime();
        DnsCryptUpstream upstream = new DnsCryptUpstream(stamp, new ShadowsocksDialer(node, direct));
        byte[] answer = upstream.query(DnsWire.query(TEST_NAME, DnsWire.TYPE_A));
        DnsWire.answers(answer, DnsWire.TYPE_A); // throws if the resolver reported an error
        return (System.nanoTime() - start) / 1_000_000;
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
