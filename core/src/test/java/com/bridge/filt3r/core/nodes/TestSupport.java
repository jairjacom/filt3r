package com.bridge.filt3r.core.nodes;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;

/** Shared fakes for the node tests: a node factory, a probe that answers at once, a manual clock. */
final class TestSupport {

    private TestSupport() {
    }

    static ShadowsocksNode node(String host) {
        return ShadowsocksNode.parse("ss://aes-256-gcm:pw@" + host + ":8388#" + host);
    }

    /** A manager whose clock only moves when the test says so. */
    static final class Clock implements java.util.function.LongSupplier {
        long now = 1_000_000;

        @Override
        public long getAsLong() {
            return now;
        }
    }

    /** Nodes in {@code dead} fail both probe calls; every other node answers in 100 ms. */
    static final class FakeProbe implements NodeProbe {
        final Set<String> dead = new HashSet<>();
        final List<String> calls = new java.util.ArrayList<>();

        @Override
        public synchronized boolean works(ShadowsocksNode node) {
            calls.add("works:" + node.host);
            return !dead.contains(node.host);
        }

        @Override
        public synchronized long test(ShadowsocksNode node) throws IOException {
            calls.add("test:" + node.host);
            if (dead.contains(node.host)) {
                throw new IOException("dead");
            }
            return 100;
        }
    }

    /** Runs every task on the calling thread, so a test sees the finished result. */
    static final class InlineExecutor extends AbstractExecutorService {
        private boolean shut;

        @Override
        public void execute(Runnable r) {
            r.run();
        }

        @Override
        public void shutdown() {
            shut = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shut = true;
            return List.of();
        }

        @Override
        public boolean isShutdown() {
            return shut;
        }

        @Override
        public boolean isTerminated() {
            return shut;
        }

        @Override
        public boolean awaitTermination(long t, TimeUnit u) {
            return true;
        }
    }
}
