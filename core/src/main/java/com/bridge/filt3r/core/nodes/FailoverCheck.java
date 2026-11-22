package com.bridge.filt3r.core.nodes;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;

/**
 * Verifies a failing current node off the traffic path: while it checks whether the node is really
 * dead, it tests the best standby nodes, so that when the node is blacklisted the manager picks
 * from nodes that answered just now. Live traffic never waits on this class.
 */
public final class FailoverCheck {

    static final int STANDBYS = 5;
    static final long STANDBY_WAIT_MS = 3_000;  // after the suspect is found dead
    static final long STANDBY_MAX_MS = 25_000;  // longer than one dead-node test (~22 s)

    private final NodeManager manager;
    private final NodeProbe probe;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService pool;

    public FailoverCheck(NodeManager manager, NodeProbe probe) {
        this(manager, probe, Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "failover-check");
            t.setDaemon(true);
            return t;
        }));
    }

    /** The executor runs the check and the standby tests; tests pass one that runs tasks inline. */
    FailoverCheck(NodeManager manager, NodeProbe probe, ExecutorService pool) {
        this.manager = manager;
        this.probe = probe;
        this.pool = pool;
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
                        manager.recordFailure(n);
                    } finally {
                        done.countDown();
                    }
                });
            }
            boolean alive = probe.works(suspect);
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
