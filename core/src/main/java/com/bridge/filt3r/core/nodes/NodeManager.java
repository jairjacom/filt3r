package com.bridge.filt3r.core.nodes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.LongSupplier;

import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;

/**
 * Keeps the node list, ranks nodes by the median of their recent test times, blacklists a node
 * after {@value #FAILURES_TO_BLACKLIST} failures in a row, and keeps using the current node until
 * it dies. A blacklisted node is tried again after a while (5 minutes, doubling per repeat up to
 * an hour), and failures are not counted while the device has no network, so a short outage never
 * leaves the list empty. All methods are thread safe.
 */
public final class NodeManager {

    public static final int FAILURES_TO_BLACKLIST = 3;
    static final long BLACKLIST_BASE_MS = 5 * 60_000L;
    static final long BLACKLIST_MAX_MS = 60 * 60_000L;
    private static final int SAMPLES = 5;

    /** Called when a pin is dropped by the manager (not by {@link #unpin}); outside the lock. */
    public interface PinListener {
        /** @param dead true if the node failed {@value NodeManager#FAILURES_TO_BLACKLIST} times, false if it left the list */
        void onPinCleared(ShadowsocksNode node, boolean dead);
    }

    /** Called (outside the manager's lock) whenever the current node changes; null means none is usable. */
    public interface Listener {
        void onCurrentChanged(ShadowsocksNode node);
    }

    private static final class Entry {
        final ShadowsocksNode node;
        final long[] samples = new long[SAMPLES];
        int count;      // samples stored so far (at most SAMPLES)
        int next;       // ring position
        int failures;   // consecutive
        int strikes;    // blacklistings since the last working test
        long blacklistedUntil; // clock time (ms); 0 = never blacklisted
        Boolean ipv6;   // null until probed: does the node reach IPv6 destinations?
        long speedBps = -1; // download speed through the node, -1 if never measured

        Entry(ShadowsocksNode node) {
            this.node = node;
        }

        void addSample(long ms) {
            samples[next] = ms;
            next = (next + 1) % SAMPLES;
            count = Math.min(count + 1, SAMPLES);
        }

        /** @return the median of the stored samples, or Long.MAX_VALUE if there are none */
        long median() {
            if (count == 0) {
                return Long.MAX_VALUE;
            }
            long[] s = Arrays.copyOf(samples, count);
            Arrays.sort(s);
            return s[count / 2];
        }
    }

    private final Map<String, Entry> entries = new HashMap<>();
    private final List<String> order = new ArrayList<>();
    private final LongSupplier clock;
    private boolean online = true; // false while the device has no network
    private final Listener listener;
    private PinListener pinListener;
    private Entry current;
    private String pinned; // key of the pinned node, or null
    private long lastRoundAt; // wall-clock ms of the last finished test round, 0 if none yet

    public NodeManager(Listener listener) {
        this(listener, () -> System.nanoTime() / 1_000_000); // monotonic: a clock change must not stretch a blacklisting
    }

    NodeManager(Listener listener, LongSupplier clock) {
        this.listener = listener;
        this.clock = clock;
    }

    private boolean blacklisted(Entry e) {
        return clock.getAsLong() < e.blacklistedUntil;
    }

    /** False while {@link #setOnline} was last told the device has no network. */
    public synchronized boolean isOnline() {
        return online;
    }

    /**
     * Tells the manager whether the device has a network. Nothing is counted against a node while
     * it has none. Whenever the network comes back (or changes) every failure count and blacklisting
     * is cleared, because an outage looks exactly like every node dying.
     */
    public void setOnline(boolean value) {
        boolean changed = false;
        synchronized (this) {
            online = value;
            if (value) {
                for (Entry e : entries.values()) {
                    e.failures = 0;
                    e.strikes = 0;
                    e.blacklistedUntil = 0;
                }
                changed = reselect(current);
            }
        }
        notifyChange(changed);
    }

    private static String key(ShadowsocksNode n) {
        return n.host + ":" + n.port;
    }

    /**
     * Replaces the node list with a fresh subscription result. Test times of nodes that are still
     * listed are kept; failures and blacklisting start over.
     */
    public void setNodes(List<ShadowsocksNode> nodes) {
        boolean changed;
        ShadowsocksNode unpinned = null;
        synchronized (this) {
            Map<String, Entry> old = new HashMap<>(entries);
            entries.clear();
            order.clear();
            for (ShadowsocksNode n : nodes) {
                String k = key(n);
                if (entries.containsKey(k)) {
                    continue;
                }
                Entry e = new Entry(n);
                Entry before = old.get(k);
                if (before != null) {
                    System.arraycopy(before.samples, 0, e.samples, 0, SAMPLES);
                    e.count = before.count;
                    e.next = before.next;
                    e.ipv6 = before.ipv6;
                    e.speedBps = before.speedBps;
                }
                entries.put(k, e);
                order.add(k);
            }
            // A pin on a node that left the list is dropped (a restored pin has no old entry yet).
            if (pinned != null && !entries.containsKey(pinned)) {
                Entry gone = old.get(pinned);
                pinned = null;
                unpinned = gone == null ? null : gone.node;
            }
            Entry keep = current == null ? null : entries.get(key(current.node));
            changed = reselect(keep);
        }
        notifyChange(changed);
        if (unpinned != null && pinListener != null) {
            pinListener.onPinCleared(unpinned, false);
        }
    }

    /** Switches to the best-ranked tested node. Used after a first test round, when "first to answer" is not "best". */
    public void selectBest() {
        boolean changed;
        synchronized (this) {
            changed = reselect(null);
        }
        notifyChange(changed);
    }

    /** The node to use now, or null if none has been tested and found working yet. */
    public synchronized ShadowsocksNode current() {
        return current == null ? null : current.node;
    }

    /** Wall-clock time (ms) when the last test round finished, or 0 if none has finished yet. */
    public synchronized long lastRoundMs() {
        return lastRoundAt;
    }

    /** A test round just finished (called by the tester, whatever the results were). */
    public synchronized void markRoundDone() {
        lastRoundAt = System.currentTimeMillis();
    }

    public synchronized int size() {
        return entries.size();
    }

    public synchronized int blacklistedCount() {
        int n = 0;
        for (Entry e : entries.values()) {
            if (blacklisted(e)) {
                n++;
            }
        }
        return n;
    }

    /** Every node, best first: working ones by median time, then untested ones, then blacklisted ones. */
    public synchronized List<NodeInfo> snapshot() {
        List<NodeInfo> out = new ArrayList<>(order.size());
        for (String k : order) {
            Entry e = entries.get(k);
            NodeInfo.State state = blacklisted(e) ? NodeInfo.State.BLACKLISTED
                    : e.count > 0 ? NodeInfo.State.WORKING : NodeInfo.State.UNTESTED;
            out.add(new NodeInfo(e.node, state, e.count > 0 ? e.median() : -1, e.failures, e == current, e.ipv6,
                    k.equals(pinned), e.speedBps));
        }
        // Stable sort: ties keep subscription order.
        out.sort((a, b) -> {
            int byState = a.state.compareTo(b.state);
            return byState != 0 ? byState : Long.compare(a.medianMs, b.medianMs);
        });
        return out;
    }

    /** Records whether the node reaches IPv6 destinations (the result of a probe). */
    public synchronized void setIpv6(ShadowsocksNode node, boolean reachable) {
        Entry e = entries.get(key(node));
        if (e != null) {
            e.ipv6 = reachable;
        }
    }

    /** Records a download speed measured through the node, in bytes per second. */
    public synchronized void setSpeed(ShadowsocksNode node, long bytesPerSecond) {
        Entry e = entries.get(key(node));
        if (e != null) {
            e.speedBps = bytesPerSecond;
        }
    }

    /**
     * Pins a node: it carries traffic whenever it is usable. Only a node that answered a test can
     * be pinned.
     *
     * @return false if the node is unknown, blacklisted or untested
     */
    public boolean pin(ShadowsocksNode node) {
        boolean changed;
        synchronized (this) {
            Entry e = entries.get(key(node));
            if (e == null || blacklisted(e) || e.count == 0) {
                return false;
            }
            pinned = key(node);
            changed = reselect(current);
        }
        notifyChange(changed);
        return true;
    }

    /** Removes the pin; the current node keeps carrying traffic until it dies. */
    public synchronized void unpin() {
        pinned = null;
    }

    /** Restores a pin saved earlier (see {@link #keyOf}); it applies once that node has answered a test. */
    public synchronized void restorePin(String key) {
        pinned = key;
    }

    /** The pinned node's key, or null. */
    public synchronized String pinnedKey() {
        return pinned;
    }

    /** Stable id of a node, for saving a pin. */
    public static String keyOf(ShadowsocksNode n) {
        return key(n);
    }

    public synchronized void setPinListener(PinListener l) {
        pinListener = l;
    }

    /** @return true only if there is a current node that was probed and has IPv6 egress */
    public synchronized boolean currentHasIpv6() {
        return current != null && Boolean.TRUE.equals(current.ipv6);
    }

    /** @return true if there is a current node that has not been probed for IPv6 yet */
    public synchronized boolean currentIpv6Unknown() {
        return current != null && current.ipv6 == null;
    }

    /** A test round finished for this node: a working time in ms resets its failure count and lifts a blacklisting. */
    public void recordTest(ShadowsocksNode node, long ms) {
        boolean changed = false;
        synchronized (this) {
            Entry e = entries.get(key(node));
            if (e == null) {
                return;
            }
            e.addSample(ms);
            e.failures = 0;
            e.strikes = 0;
            e.blacklistedUntil = 0;
            if (current == null || key(node).equals(pinned)) {
                changed = reselect(current);
            }
        }
        notifyChange(changed);
    }

    /** Ordinary traffic worked through this node. */
    public synchronized void recordSuccess(ShadowsocksNode node) {
        Entry e = entries.get(key(node));
        if (e != null) {
            e.failures = 0;
        }
    }

    /**
     * A connection or test through this node failed; the third one in a row blacklists it. Ignored
     * while the device has no network. A node that fails again right after its blacklisting ended
     * is blacklisted again for twice as long.
     */
    public void recordFailure(ShadowsocksNode node) {
        boolean changed = false;
        ShadowsocksNode unpinned = null;
        synchronized (this) {
            Entry e = entries.get(key(node));
            if (!online || e == null || blacklisted(e)) {
                return;
            }
            if (++e.failures >= FAILURES_TO_BLACKLIST) {
                long span = Math.min(BLACKLIST_BASE_MS << Math.min(e.strikes, 10), BLACKLIST_MAX_MS);
                e.strikes++;
                e.blacklistedUntil = clock.getAsLong() + span;
                if (key(node).equals(pinned)) {
                    pinned = null;
                    unpinned = e.node;
                }
                if (e == current) {
                    changed = reselect(null);
                }
            }
        }
        notifyChange(changed);
        if (unpinned != null && pinListener != null) {
            pinListener.onPinCleared(unpinned, true);
        }
    }

    /**
     * Nodes to test in the next round: the {@code top} best-ranked usable ones, a few random others
     * and every node that has never been tested.
     */
    public synchronized List<ShadowsocksNode> pickForTest(int top, int random, Random rng) {
        List<Entry> usable = new ArrayList<>();
        for (String k : order) {
            Entry e = entries.get(k);
            if (!blacklisted(e)) {
                usable.add(e);
            }
        }
        if (usable.isEmpty()) {
            // Every node is blacklisted (a long outage, a captive portal): test them anyway.
            // A working answer lifts the blacklisting; waiting it out could take an hour.
            for (String k : order) {
                usable.add(entries.get(k));
            }
        }
        List<Entry> ranked = new ArrayList<>(usable);
        ranked.sort((a, b) -> Long.compare(a.median(), b.median()));
        List<ShadowsocksNode> out = new ArrayList<>();
        List<Entry> rest = new ArrayList<>();
        for (int i = 0; i < ranked.size(); i++) {
            if (i < top && ranked.get(i).count > 0) {
                out.add(ranked.get(i).node);
            } else {
                rest.add(ranked.get(i));
            }
        }
        Collections.shuffle(rest, rng);
        for (int i = 0; i < Math.min(random, rest.size()); i++) {
            out.add(rest.get(i).node);
        }
        Entry p = pinned == null ? null : entries.get(pinned);
        if (p != null && !blacklisted(p) && !out.contains(p.node)) {
            out.add(p.node);
        }
        return out;
    }

    /** Counts only: no addresses, no passwords. */
    public synchronized String summary() {
        int tested = 0;
        for (Entry e : entries.values()) {
            if (e.count > 0) {
                tested++;
            }
        }
        return "nodes=" + entries.size() + " tested=" + tested + " blacklisted=" + blacklistedCount()
                + " current=" + (current == null ? "none" : current.median() + "ms");
    }

    /**
     * The pinned node wins while it is usable and has answered a test; otherwise keeps {@code keep}
     * if it is usable, otherwise picks the best tested node. @return true if the current node changed
     */
    private boolean reselect(Entry keep) {
        Entry before = current;
        Entry p = pinned == null ? null : entries.get(pinned);
        if (p != null && !blacklisted(p) && p.count > 0) {
            current = p;
        } else {
            current = keep != null && !blacklisted(keep) ? keep : best();
        }
        return current != before;
    }

    private Entry best() {
        Entry best = null;
        for (String k : order) {
            Entry e = entries.get(k);
            if (!blacklisted(e) && e.count > 0 && (best == null || e.median() < best.median())) {
                best = e;
            }
        }
        return best;
    }

    private void notifyChange(boolean changed) {
        if (changed) {
            listener.onCurrentChanged(current());
        }
    }
}
