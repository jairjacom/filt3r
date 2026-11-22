package com.bridge.filt3r.core.nodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;

/** Which nodes the next test round takes: the best ones, then the least recently tested, then the pin. */
class PickForTestTest {

    private TestSupport.Clock clock;
    private NodeManager manager;
    private ShadowsocksNode a, b, c, d, e, f;

    @BeforeEach
    void setUp() {
        clock = new TestSupport.Clock();
        manager = new NodeManager(n -> { }, clock);
        a = TestSupport.node("a.example");
        b = TestSupport.node("b.example");
        c = TestSupport.node("c.example");
        d = TestSupport.node("d.example");
        e = TestSupport.node("e.example");
        f = TestSupport.node("f.example");
        manager.setNodes(List.of(a, b, c, d, e, f));
        manager.recordTest(c, 900); // tested first, so the stalest of the tested ones
        clock.now += 1_000;
        manager.recordTest(d, 900);
        clock.now += 1_000;
        manager.recordTest(a, 100);
        manager.recordTest(b, 200);
        // e and f have never been tested
    }

    @Test
    void takesTheBestFirstThenNeverTestedThenTheOldest() {
        List<ShadowsocksNode> pick = manager.pickForTest(1, 3, new Random(1));
        assertEquals(a, pick.get(0));
        assertEquals(Set.of(e, f), Set.copyOf(pick.subList(1, 3)));
        assertEquals(c, pick.get(3)); // the oldest tested node comes after the never-tested ones
        assertEquals(4, pick.size());
    }

    @Test
    void aBlacklistedNodeIsNotPicked() {
        manager.markDead(f);
        List<ShadowsocksNode> pick = manager.pickForTest(1, 5, new Random(1));
        assertFalse(pick.contains(f));
        assertTrue(pick.contains(e));
    }

    @Test
    void thePinnedNodeIsAddedWhenNotAlreadyPicked() {
        assertTrue(manager.pin(d));
        List<ShadowsocksNode> pick = manager.pickForTest(1, 1, new Random(1));
        assertEquals(3, pick.size());
        assertEquals(d, pick.get(2));
    }
}
