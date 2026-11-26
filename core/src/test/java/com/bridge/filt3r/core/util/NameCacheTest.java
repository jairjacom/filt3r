package com.bridge.filt3r.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/** Which host name an app asked for before it connected to an IP: only that app is told, only for a while. */
class NameCacheTest {

    private final AtomicLong now = new AtomicLong(0);
    private final NameCache cache = new NameCache(now::get);

    @Test
    void aNameIsOnlyGivenBackToTheAppThatResolvedIt() {
        cache.put("org.example.chrome", "203.0.113.7", "shop.example");

        assertEquals("shop.example", cache.get("org.example.chrome", "203.0.113.7"));
        // another app may reach the same CDN address for a different site: no guessing
        assertNull(cache.get("org.example.other", "203.0.113.7"));
        assertNull(cache.get("org.example.chrome", "203.0.113.8"));
    }

    @Test
    void aNameIsForgottenTenMinutesAfterItWasLastResolved() {
        cache.put("a", "203.0.113.7", "shop.example");

        now.set(599_999);
        assertEquals("shop.example", cache.get("a", "203.0.113.7"));
        now.set(600_000);
        assertNull(cache.get("a", "203.0.113.7"));

        // resolving it again starts the ten minutes over
        cache.put("a", "203.0.113.7", "shop.example");
        now.set(1_199_999);
        assertEquals("shop.example", cache.get("a", "203.0.113.7"));
    }

    @Test
    void atTwoThousandEntriesTheLongestUnrefreshedOneGoesFirst() {
        for (int i = 0; i < 2000; i++) {
            cache.put("a", "ip" + i, "name" + i);
        }
        cache.put("a", "ip0", "name0"); // resolved again: now the newest
        cache.put("a", "ip2000", "name2000");

        assertEquals("name0", cache.get("a", "ip0"));
        assertNull(cache.get("a", "ip1"));
        assertEquals("name2000", cache.get("a", "ip2000"));
        assertEquals("name2", cache.get("a", "ip2"));
    }
}
