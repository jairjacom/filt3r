package com.bridge.filt3r.core.nodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

import com.bridge.filt3r.core.dns.DnsWire;
import com.bridge.filt3r.core.dnscrypt.DnsCryptStamp;
import com.bridge.filt3r.core.dnscrypt.DnsCryptUpstream;
import com.bridge.filt3r.core.shadowsocks.ShadowsocksDialer;
import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;
import com.bridge.filt3r.core.socks.Dialer;
import com.bridge.filt3r.core.http.HttpsFetcher;
import com.bridge.filt3r.core.dns.UpstreamNameResolver;

/**
 * Tests nodes with one DNSCrypt-over-TCP query (certificate fetch included) sent through each node,
 * and checks IPv6 egress with the same query sent to the resolver's IPv6 address.
 */
public final class NodeTester implements NodeProbe {

    private static final String TEST_NAME = "example.com";
    private static final long ROUND_TIMEOUT_S = 40;
    private static final String SPEED_URL = "https://speed.cloudflare.com/__down?bytes=1000000";
    private static final long SPEED_MAX_MS = 15_000;

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
    @Override
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
        startRound(manager, nodes, threads).awaitDone();
    }

    /** A test round that is running in the background; see {@link #startRound}. */
    public static final class Round {
        private final NodeManager manager;
        private final ExecutorService pool;
        private final long deadlineNs;
        private final CountDownLatch firstOk = new CountDownLatch(1);
        private final CountDownLatch allDone;
        private final AtomicBoolean anyOk = new AtomicBoolean();
        private final AtomicBoolean marked = new AtomicBoolean();

        private Round(NodeManager manager, ExecutorService pool, int count) {
            this.manager = manager;
            this.pool = pool;
            this.allDone = new CountDownLatch(count);
            this.deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(ROUND_TIMEOUT_S);
            if (count == 0) {
                firstOk.countDown();
            }
        }

        private long remainingNs() {
            return Math.max(0, deadlineNs - System.nanoTime());
        }

        /**
         * Waits until a node answered, then up to {@code graceMs} more for the rest of the round
         * (so the best of the fast ones can be chosen).
         *
         * @return true if any node answered
         */
        public boolean awaitFirstSuccess(long graceMs) {
            try {
                firstOk.await(remainingNs(), TimeUnit.NANOSECONDS);
                if (anyOk.get()) {
                    allDone.await(Math.min(TimeUnit.MILLISECONDS.toNanos(graceMs), remainingNs()), TimeUnit.NANOSECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                pool.shutdownNow();
            }
            return anyOk.get();
        }

        /** Waits until the round is finished or its deadline passed, then marks the round done (once). */
        public void awaitDone() {
            try {
                if (!pool.awaitTermination(remainingNs(), TimeUnit.NANOSECONDS)) {
                    pool.shutdownNow();
                }
            } catch (InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
            }
            if (marked.compareAndSet(false, true)) {
                manager.markRoundDone();
            }
        }

        /** Stops the tests that are still running. */
        public void cancel() {
            pool.shutdownNow();
        }
    }

    /** Starts testing the nodes in parallel and returns at once. */
    public Round startRound(NodeManager manager, List<ShadowsocksNode> nodes, int threads) {
        return start(manager, nodes, threads, this);
    }

    static Round start(NodeManager manager, List<ShadowsocksNode> nodes, int threads, NodeProbe probe) {
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(threads, nodes.size())));
        Round round = new Round(manager, pool, nodes.size());
        for (ShadowsocksNode n : nodes) {
            pool.execute(() -> {
                try {
                    manager.recordTest(n, probe.test(n));
                    round.anyOk.set(true);
                    round.firstOk.countDown();
                } catch (IOException | RuntimeException e) {
                    manager.recordFailure(n);
                } finally {
                    round.allDone.countDown();
                    if (round.allDone.getCount() == 0) {
                        round.firstOk.countDown();
                    }
                }
            });
        }
        pool.shutdown();
        return round;
    }

    /**
     * Downloads 1 MB through the node and times the body only (connect and TLS are excluded), so
     * the figure is throughput, not latency. A download that takes longer than
     * {@value #SPEED_MAX_MS} ms is cut off and the rate so far is used.
     *
     * @return bytes per second; @throws IOException if nothing arrived
     */
    public long speed(ShadowsocksNode node) throws IOException {
        ShadowsocksDialer dialer = new ShadowsocksDialer(node, direct);
        HttpsFetcher fetcher = new HttpsFetcher(dialer,
                new UpstreamNameResolver(new DnsCryptUpstream(stamp, dialer)));
        try (InputStream in = fetcher.stream(SPEED_URL)) {
            long start = System.nanoTime();
            long total = 0;
            byte[] buf = new byte[16 * 1024];
            int n;
            long ms = 0;
            while ((n = in.read(buf)) >= 0) {
                total += n;
                ms = (System.nanoTime() - start) / 1_000_000;
                if (ms > SPEED_MAX_MS) {
                    break;
                }
            }
            if (total == 0) {
                throw new IOException("no data");
            }
            return total * 1000 / Math.max(1, ms);
        }
    }

    /**
     * Speed-tests the nodes one after another (parallel runs would share the phone's bandwidth)
     * and records each result. A failed speed test says nothing about whether the node is alive,
     * so it is not counted as a node failure.
     */
    public void speedAndRecord(NodeManager manager, List<ShadowsocksNode> nodes) {
        for (ShadowsocksNode n : nodes) {
            try {
                manager.setSpeed(n, speed(n));
            } catch (IOException | RuntimeException e) {
                // leaves the earlier figure in place
            }
        }
    }

    /**
     * Probes the nodes for IPv6 egress in parallel and records each result. A node that cannot
     * be probed counts as having no IPv6 (the safe side). Blocks until done or timed out.
     */
    public void ipv6AndRecord(NodeManager manager, List<ShadowsocksNode> nodes, int threads) {
        if (nodes.isEmpty()) {
            return;
        }
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(threads, nodes.size())));
        for (ShadowsocksNode n : nodes) {
            pool.execute(() -> manager.setIpv6(n, hasIpv6(n)));
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
