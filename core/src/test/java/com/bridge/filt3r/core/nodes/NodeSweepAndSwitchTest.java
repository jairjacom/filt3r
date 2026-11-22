package com.bridge.filt3r.core.nodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;

/** Pins down the full scan (sweep) and the two "switch to a better node" rules. */
class NodeSweepAndSwitchTest {

    private TestSupport.Clock clock;
    private NodeManager manager;
    private ShadowsocksNode a;
    private ShadowsocksNode b;
    private ShadowsocksNode c;
    private ShadowsocksNode d;

    @BeforeEach
    void setUp() {
        clock = new TestSupport.Clock();
        manager = new NodeManager(n -> { }, clock);
        a = TestSupport.node("a.example");
        b = TestSupport.node("b.example");
        c = TestSupport.node("c.example");
        d = TestSupport.node("d.example");
        manager.setNodes(List.of(a, b, c, d));
    }

    // ---- full scan

    @Test
    void noBatchWhileNoScanRuns() {
        assertTrue(manager.nextSweepBatch(0, 1, 10, new Random(1)).isEmpty());
    }

    @Test
    void aScanHandsOutEveryNodeOnceAndThenNothing() {
        manager.recordTest(a, 100); // tried before the scan began: still due
        clock.now += 1_000;
        manager.startSweep();
        List<ShadowsocksNode> batch = manager.nextSweepBatch(0, 1, 10, new Random(1));
        assertEquals(4, batch.size());
        clock.now += 1;
        for (ShadowsocksNode n : batch) {
            manager.recordTest(n, 100);
        }
        assertTrue(manager.nextSweepBatch(0, 1, 10, new Random(1)).isEmpty());
    }

    @Test
    void theBatchSpreadsTheWorkOverTheWindowAndNeverExceedsMax() {
        manager.startSweep();
        // 4 nodes over 10 steps: one per batch
        assertEquals(1, manager.nextSweepBatch(100_000, 10_000, 10, new Random(1)).size());
        // window already over: everything at once, capped by max
        assertEquals(2, manager.nextSweepBatch(0, 10_000, 2, new Random(1)).size());
    }

    @Test
    void aBlacklistedNodeIsNotHandedOut() {
        manager.recordTest(a, 100); // tried before the scan began: still due
        manager.markDead(b);
        clock.now += 1_000;
        manager.startSweep();
        assertEquals(3, manager.nextSweepBatch(0, 1, 10, new Random(1)).size());
    }

    @Test
    void scanStatusCountsDoneTotalAndFastNodes() {
        NodeManager.ScanStatus before = manager.scanStatus();
        assertFalse(before.running);
        assertEquals(-1, before.doneAgoMs);
        clock.now += 1_000;
        manager.startSweep();
        clock.now += 1;
        manager.recordTest(a, 100);
        manager.recordTest(b, 2_000);
        NodeManager.ScanStatus during = manager.scanStatus();
        assertTrue(during.running);
        assertEquals(2, during.done);
        assertEquals(4, during.total);
        assertEquals(1, during.fast);
    }

    @Test
    void sweepDueIsFalseWhileRunningAndUntilTheRepeatTimeHasPassed() {
        assertTrue(manager.sweepDue(60_000));
        manager.startSweep();
        assertTrue(manager.sweeping());
        assertFalse(manager.sweepDue(60_000));
        manager.finishSweep();
        assertFalse(manager.sweeping());
        assertFalse(manager.sweepDue(60_000));
        clock.now += 60_000;
        assertTrue(manager.sweepDue(60_000));
        assertEquals(60_000, manager.scanStatus().doneAgoMs);
    }

    // ---- switching

    /** a is current and slow (1000 ms); b answered at 100 ms just now. */
    private void slowCurrentFastCandidate() {
        manager.recordTest(a, 1_000);
        manager.recordTest(b, 100);
        assertSame(a, manager.current());
    }

    @Test
    void switchIfFasterMovesToAClearlyFasterNode() {
        slowCurrentFastCandidate();
        assertTrue(manager.switchIfFaster(0.8, 100, 1));
        assertSame(b, manager.current());
    }

    @Test
    void switchIfFasterIgnoresACandidateWithTooFewSamplesOrTooSmallAGain() {
        slowCurrentFastCandidate();
        assertFalse(manager.switchIfFaster(0.8, 100, 2));   // b has one sample
        assertFalse(manager.switchIfFaster(0.8, 5_000, 1)); // gain 900 ms is not enough
        assertFalse(manager.switchIfFaster(20, 100, 1));    // 100 * 20 > 1000
        assertSame(a, manager.current());
    }

    @Test
    void aPinnedNodeIsNeverLeftByEitherSwitchRule() {
        slowCurrentFastCandidate();
        assertTrue(manager.pin(a));
        assertFalse(manager.switchIfFaster(0.8, 100, 1));
        assertFalse(manager.switchIfSlow(1, 100));
        assertFalse(manager.currentIsSlow());
        assertSame(a, manager.current());
    }

    @Test
    void switchIfSlowMovesOnlyAwayFromASlowCurrentNode() {
        slowCurrentFastCandidate();
        assertTrue(manager.currentIsSlow());
        assertTrue(manager.switchIfSlow(1, 100));
        assertSame(b, manager.current());
        assertFalse(manager.currentIsSlow()); // b is fast now
        manager.recordTest(c, 50);
        assertFalse(manager.switchIfSlow(1, 1)); // a fast current node stays
        assertSame(b, manager.current());
    }
}
