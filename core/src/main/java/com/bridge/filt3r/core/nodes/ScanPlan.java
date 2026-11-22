package com.bridge.filt3r.core.nodes;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import com.bridge.filt3r.core.nodes.NodeManager.Entry;
import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;

/**
 * The full scan: every node is tried once, spread over a time window, then the scan ends and the
 * next one is due after a pause. No lock of its own; {@link NodeManager} calls it with its lock held.
 */
final class ScanPlan {

    private long start = Entry.NEVER;      // clock ms the running scan began; NEVER = none
    private long doneAt = Entry.NEVER;     // clock ms the last scan ended
    private long doneWallMs;               // wall-clock ms the last scan ended (display only)

    void begin(long now) {
        start = now;
    }

    boolean running() {
        return start != Entry.NEVER;
    }

    void finish(long now, long wallNow) {
        start = Entry.NEVER;
        doneAt = now;
        doneWallMs = wallNow;
    }

    /** True when no scan runs and none ended within the last {@code repeatMs}. */
    boolean due(long now, long repeatMs) {
        return start == Entry.NEVER && (doneAt == Entry.NEVER || now - doneAt >= repeatMs);
    }

    /** ms since the last scan ended (wall clock: the monotonic one stops in deep sleep), -1 if none ended this session. */
    long doneAgoMs(long wallNow) {
        return doneAt == Entry.NEVER ? -1 : wallNow - doneWallMs;
    }

    /** Not blacklisted and not tried since the scan began. */
    private boolean unswept(Entry e, long now) {
        return now >= e.blacklistedUntil && e.lastTriedAt < start;
    }

    /** How many of the nodes the running scan has still to try; 0 when no scan runs. */
    int left(Collection<Entry> entries, long now) {
        int left = 0;
        if (start != Entry.NEVER) {
            for (Entry e : entries) {
                if (unswept(e, now)) {
                    left++;
                }
            }
        }
        return left;
    }

    /**
     * The next nodes: saved or earlier-ranked nodes first, the rest in random order. The batch
     * spreads what is left over what remains of {@code windowMs}, one batch per {@code stepMs};
     * once the window has passed, up to {@code max} per batch. Empty when no scan runs or every
     * node was tried.
     *
     * @param inOrder the nodes in subscription order
     */
    List<ShadowsocksNode> next(List<Entry> inOrder, long now, long windowMs, long stepMs, int max, Random rng) {
        List<ShadowsocksNode> out = new ArrayList<>();
        if (start == Entry.NEVER) {
            return out;
        }
        List<Entry> ranked = new ArrayList<>();
        List<Entry> rest = new ArrayList<>();
        for (Entry e : inOrder) {
            if (unswept(e, now)) {
                (e.count > 0 ? ranked : rest).add(e);
            }
        }
        int left = ranked.size() + rest.size();
        if (left == 0) {
            return out;
        }
        long stepsLeft = Math.max(1, (windowMs - (now - start)) / stepMs);
        int size = (int) Math.min(max, (left + stepsLeft - 1) / stepsLeft);
        ranked.sort((a, b) -> NodeManager.compareRank(a, b, now));
        Collections.shuffle(rest, rng);
        ranked.addAll(rest);
        for (int i = 0; i < size; i++) {
            out.add(ranked.get(i).node);
        }
        return out;
    }
}
