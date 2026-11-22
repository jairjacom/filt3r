package com.bridge.filt3r.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class StartScopeTest {

    @Test
    void closeReleasesNewestFirstAndTwiceIsHarmless() {
        StartScope scope = new StartScope();
        List<String> log = new ArrayList<>();
        assertTrue(scope.adopt(() -> log.add("first")));
        assertTrue(scope.adopt(() -> log.add("second")));
        scope.close();
        scope.close();
        assertEquals(List.of("second", "first"), log);
        assertTrue(scope.isClosed());
    }

    @Test
    void aResourceAdoptedAfterCloseIsReleasedAtOnceAndAdoptSaysNo() {
        StartScope scope = new StartScope();
        scope.close();
        List<String> log = new ArrayList<>();
        assertFalse(scope.adopt(() -> log.add("late")));
        assertEquals(List.of("late"), log);
    }

    @Test
    void aClosingResourceThatThrowsDoesNotStopTheOthers() {
        StartScope scope = new StartScope();
        List<String> log = new ArrayList<>();
        scope.adopt(() -> log.add("last"));
        scope.adopt(() -> {
            throw new IllegalStateException("boom");
        });
        scope.adopt(() -> log.add("first"));
        scope.close();
        assertEquals(List.of("first", "last"), log);
    }
}
