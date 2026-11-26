package com.bridge.filt3r.core.util;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Remembers which host name an app resolved to which IP, so a connection can be logged by name.
 * An answer is only given back to the app that asked, and only for {@value #TTL_MS} ms after it was
 * last resolved: two sites can share one CDN address, and a log that sometimes names the wrong site
 * is worse than one that shows the IP.
 */
public final class NameCache {

    private static final long TTL_MS = 600_000;
    private static final int MAX_ENTRIES = 2000;

    private static final class Slot {
        final String name;
        final long resolvedMs;

        Slot(String name, long resolvedMs) {
            this.name = name;
            this.resolvedMs = resolvedMs;
        }
    }

    private final LongSupplier clock;
    private final Map<String, Slot> names = new LinkedHashMap<>(); // oldest resolved first

    /** @param clock milliseconds since the epoch */
    public NameCache(LongSupplier clock) {
        this.clock = clock;
    }

    public synchronized void put(String appKey, String ip, String name) {
        String key = appKey + "\t" + ip;
        names.remove(key); // a fresh answer goes to the end of the line
        names.put(key, new Slot(name, clock.getAsLong()));
        if (names.size() > MAX_ENTRIES) {
            Iterator<String> oldest = names.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    /** The name this app resolved to this IP lately, or null. */
    public synchronized String get(String appKey, String ip) {
        String key = appKey + "\t" + ip;
        Slot slot = names.get(key);
        if (slot == null) {
            return null;
        }
        if (clock.getAsLong() - slot.resolvedMs >= TTL_MS) {
            names.remove(key);
            return null;
        }
        return slot.name;
    }
}
