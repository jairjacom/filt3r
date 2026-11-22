package com.bridge.filt3r.core.nodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;

/** Pins down when NodeManager counts a failure and when it blacklists a node. */
class NodeFailureCountTest {

    private TestSupport.Clock clock;
    private NodeManager manager;
    private ShadowsocksNode a;
    private ShadowsocksNode b;

    @BeforeEach
    void setUp() {
        clock = new TestSupport.Clock();
        manager = new NodeManager(n -> { }, clock);
        a = TestSupport.node("a.example");
        b = TestSupport.node("b.example");
        manager.setNodes(List.of(a, b));
        manager.recordTest(a, 100);
        manager.recordTest(b, 300);
        assertSame(a, manager.current());
    }

    @Test
    void thirdFailureInARowBlacklistsTheCurrentNodeAndSwitches() {
        manager.recordFailure(a, "test");
        manager.recordFailure(a, "test");
        assertSame(a, manager.current());
        manager.recordFailure(a, "test");
        assertSame(b, manager.current());
        assertEquals(1, manager.blacklistedCount());
    }

    @Test
    void everyCountedFailureIsLoggedWithItsReasonAndCount() {
        List<String> lines = new ArrayList<>();
        manager.setDiagnostics(lines::add);
        manager.recordFailure(a, "dial failed, SocketTimeoutException");
        manager.recordFailure(b, "test failed, IOException");
        assertEquals(List.of(
                "failure 1/3 a.example: dial failed, SocketTimeoutException, current=true",
                "failure 1/3 b.example: test failed, IOException, current=false"), lines);
    }

    @Test
    void aSuccessBetweenFailuresResetsTheCount() {
        manager.recordFailure(a, "test");
        manager.recordFailure(a, "test");
        manager.recordSuccess(a);
        manager.recordFailure(a, "test");
        manager.recordFailure(a, "test");
        assertSame(a, manager.current());
        assertEquals(0, manager.blacklistedCount());
    }

    @Test
    void failuresAreIgnoredWhileOffline() {
        manager.setOnline(false);
        for (int i = 0; i < 5; i++) {
            manager.recordFailure(a, "test");
        }
        manager.setOnline(true);
        assertSame(a, manager.current());
        assertEquals(0, manager.blacklistedCount());
    }

    @Test
    void aBlacklistedNodeComesBackAfterFiveMinutes() {
        manager.markDead(a);
        assertSame(b, manager.current());
        clock.now += NodeManager.BLACKLIST_BASE_MS + 1;
        assertEquals(0, manager.blacklistedCount());
        assertNotEquals(null, manager.current());
    }
}
