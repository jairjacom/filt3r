package com.bridge.filt3r.core.nodes;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;

/**
 * Decides whether a node is really dead, in one place.
 * <p>
 * {@link #onCurrentFailed}: verifies a failing current node off the traffic path. While it checks,
 * it tests the best standby nodes, so that when the node is blacklisted the manager picks from
 * nodes that answered just now. Live traffic never waits on it.
 * <p>
 * {@link #looksFine}: used by the DNS upstream after a failed query; probes the node, but not again
 * within {@value #PROBE_OK_MS} ms of a pass.
 */
public final class NodeHealth {

    static final int STANDBYS = 5;
    static final long STANDBY_WAIT_MS = 3_000;  // after the suspect is found dead
    static final long STANDBY_MAX_MS = 25_000;  // longer than one dead-node test (~22 s)
    static final long PROBE_OK_MS = 10_000;     // a node that passed a probe is not probed again this soon

    private final NodeManager manager;
    private final NodeProbe probe;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService pool;
    private final LongSupplier clock;
    private ShadowsocksNode probedNode;   // guarded by this
    private long probedOkAt;              // guarded by this

    public NodeHealth(NodeManager manager, NodeProbe probe) {
        this(manager, probe, System::currentTimeMillis, Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "failover-check");
            t.setDaemon(true);
            return t;
        }));
    }

    /** The executor runs the check and the standby tests; tests pass one that runs tasks inline. */
    NodeHealth(NodeManager manager, NodeProbe probe, LongSupplier clock, ExecutorService pool) {
        this.manager = manager;
        this.probe = probe;
        this.clock = clock;
        this.pool = pool;
    }

    /** False if the node fails a probe now; true if it passed one within the last {@value #PROBE_OK_MS} ms or passes now. */
    public boolean looksFine(ShadowsocksNode node) {
        synchronized (this) {
            if (node == probedNode && clock.getAsLong() - probedOkAt < PROBE_OK_MS) {
                return true;
            }
        }
        boolean works = probe.works(node);
        manager.diagnose("probe after a failed DNS query, " + node.name + ": " + (works ? "passed" : "failed"));
        if (works) {
            synchronized (this) {
                probedNode = node;
                probedOkAt = clock.getAsLong();
            }
        }
        return works;
    }

    /** The current node failed but is not blacklisted yet. Returns at once; one check runs at a time. */
    public void onCurrentFailed(ShadowsocksNode suspect) {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            pool.execute(() -> run(suspect));
        } catch (RejectedExecutionException e) {
            running.set(false);
        }
    }

    public void close() {
        pool.shutdownNow();
    }

    private void run(ShadowsocksNode suspect) {
        try {
            if (!manager.isOnline() || manager.current() != suspect) {
                return;
            }
            List<ShadowsocksNode> standbys = manager.standbys(STANDBYS);
            CountDownLatch done = new CountDownLatch(standbys.size());
            for (ShadowsocksNode n : standbys) {
                pool.execute(() -> {
                    try {
                        manager.recordTest(n, probe.test(n));
                    } catch (IOException | RuntimeException e) {
                        manager.recordFailure(n, "standby test failed, " + e.getClass().getSimpleName());
                    } finally {
                        done.countDown();
                    }
                });
            }
            boolean alive = probe.works(suspect);
            manager.diagnose("failover check " + suspect.name + ": probe " + (alive ? "passed, node kept"
                    : "failed, blacklisting it") + ", standbys tested: " + standbys.size());
            if (!alive) {
                done.await(STANDBY_WAIT_MS, TimeUnit.MILLISECONDS);
                manager.markDead(suspect);
            }
            done.await(STANDBY_MAX_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RejectedExecutionException e) {
            // closed while running
        } finally {
            running.set(false);
        }
    }
}
