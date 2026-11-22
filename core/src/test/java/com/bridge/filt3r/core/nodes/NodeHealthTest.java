package com.bridge.filt3r.core.nodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;

/** Pins down what NodeHealth does when the current node fails but is not blacklisted yet. */
class NodeHealthTest {

    private TestSupport.Clock clock = new TestSupport.Clock();
    private NodeManager manager;
    private TestSupport.FakeProbe probe;
    private NodeHealth check;
    private ShadowsocksNode a;
    private ShadowsocksNode b;
    private ShadowsocksNode c;

    @BeforeEach
    void setUp() {
        manager = new NodeManager(n -> { }, clock);
        probe = new TestSupport.FakeProbe();
        check = new NodeHealth(manager, probe, clock, new TestSupport.InlineExecutor());
        a = TestSupport.node("a.example");
        b = TestSupport.node("b.example");
        c = TestSupport.node("c.example");
        manager.setNodes(List.of(a, b, c));
        manager.recordTest(a, 100);
        manager.recordTest(b, 200);
        manager.recordTest(c, 300);
        manager.setFailureListener(check::onCurrentFailed);
        assertSame(a, manager.current());
    }

    @Test
    void aFailingNodeThatFailsItsProbeIsBlacklistedAtOnceAndAStandbyTakesOver() {
        probe.dead.add("a.example");
        manager.recordFailure(a, "test"); // first failure: suspect, not blacklisted; the listener runs the check
        assertSame(b, manager.current());
        assertEquals(1, manager.blacklistedCount());
        assertTrue(probe.calls.contains("test:b.example"), "standbys are tested while the suspect is checked");
        assertTrue(probe.calls.contains("test:c.example"));
    }

    @Test
    void aFailingNodeThatPassesItsProbeStaysCurrentAndUnblacklisted() {
        manager.recordFailure(a, "test");
        assertSame(a, manager.current());
        assertEquals(0, manager.blacklistedCount());
    }

    @Test
    void aStandbyThatFailsItsTestIsNotPickedAtFailover() {
        probe.dead.add("a.example");
        probe.dead.add("b.example");
        manager.recordFailure(a, "test");
        assertSame(c, manager.current());
    }

    @Test
    void nothingRunsWhileOffline() {
        probe.dead.add("a.example");
        manager.setOnline(false);
        manager.recordFailure(a, "test");
        assertTrue(probe.calls.isEmpty());
        assertSame(a, manager.current());
    }

    @Test
    void looksFineProbesAgainAfterTenSecondsButNotBefore() {
        assertTrue(check.looksFine(a));
        assertTrue(check.looksFine(a));
        assertEquals(1, probe.calls.stream().filter("works:a.example"::equals).count());
        clock.now += NodeHealth.PROBE_OK_MS + 1;
        assertTrue(check.looksFine(a));
        assertEquals(2, probe.calls.stream().filter("works:a.example"::equals).count());
    }

    @Test
    void looksFineIsFalseWhenTheProbeFailsAndIsNotCached() {
        probe.dead.add("a.example");
        assertFalse(check.looksFine(a));
        assertFalse(check.looksFine(a));
        assertEquals(2, probe.calls.stream().filter("works:a.example"::equals).count());
    }

    @Test
    void aPassedProbeOnOneNodeDoesNotVouchForAnother() {
        assertTrue(check.looksFine(a));
        probe.dead.add("b.example");
        assertFalse(check.looksFine(b));
    }
}
