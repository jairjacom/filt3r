package com.bridge.filt3r.core.nodes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;

/**
 * Keeps the node list, ranks nodes by the median of their recent test times, blacklists a node
 * after {@value #FAILURES_TO_BLACKLIST} failures in a row (until the next subscription update), and
 * keeps using the current node until it dies. All methods are thread safe.
 */
public final class NodeManager {

    public static final int FAILURES_TO_BLACKLIST = 3;
    private static final int SAMPLES = 5;

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
        boolean blacklisted;
        Boolean ipv6;   // null until probed: does the node reach IPv6 destinations?

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
    private final Listener listener;
    private Entry current;

    public NodeManager(Listener listener) {
        this.listener = listener;
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
                }
                entries.put(k, e);
                order.add(k);
            }
            Entry keep = current == null ? null : entries.get(key(current.node));
            changed = reselect(keep);
        }
        notifyChange(changed);
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

    public synchronized int size() {
        return entries.size();
    }

    public synchronized int blacklistedCount() {
        int n = 0;
        for (Entry e : entries.values()) {
            if (e.blacklisted) {
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
            NodeInfo.State state = e.blacklisted ? NodeInfo.State.BLACKLISTED
                    : e.count > 0 ? NodeInfo.State.WORKING : NodeInfo.State.UNTESTED;
            out.add(new NodeInfo(e.node, state, e.count > 0 ? e.median() : -1, e.failures, e == current, e.ipv6));
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

    /** @return true only if there is a current node that was probed and has IPv6 egress */
    public synchronized boolean currentHasIpv6() {
        return current != null && Boolean.TRUE.equals(current.ipv6);
    }

    /** @return true if there is a current node that has not been probed for IPv6 yet */
    public synchronized boolean currentIpv6Unknown() {
        return current != null && current.ipv6 == null;
    }

    /** A test round finished for this node: a working time in ms resets its failure count. */
    public void recordTest(ShadowsocksNode node, long ms) {
        boolean changed = false;
        synchronized (this) {
            Entry e = entries.get(key(node));
            if (e == null || e.blacklisted) {
                return;
            }
            e.addSample(ms);
            e.failures = 0;
            if (current == null) {
                changed = reselect(null);
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

    /** A connection or test through this node failed; the third one in a row blacklists it. */
    public void recordFailure(ShadowsocksNode node) {
        boolean changed = false;
        synchronized (this) {
            Entry e = entries.get(key(node));
            if (e == null || e.blacklisted) {
                return;
            }
            if (++e.failures >= FAILURES_TO_BLACKLIST) {
                e.blacklisted = true;
                if (e == current) {
                    changed = reselect(null);
                }
            }
        }
        notifyChange(changed);
    }

    /**
     * Nodes to test in the next round: the {@code top} best-ranked usable ones, a few random others
     * and every node that has never been tested.
     */
    public synchronized List<ShadowsocksNode> pickForTest(int top, int random, Random rng) {
        List<Entry> usable = new ArrayList<>();
        for (String k : order) {
            Entry e = entries.get(k);
            if (!e.blacklisted) {
                usable.add(e);
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

    /** Keeps {@code keep} if it is usable, otherwise picks the best tested node. @return true if the current node changed */
    private boolean reselect(Entry keep) {
        Entry before = current;
        current = keep != null && !keep.blacklisted ? keep : best();
        return current != before;
    }

    private Entry best() {
        Entry best = null;
        for (String k : order) {
            Entry e = entries.get(k);
            if (!e.blacklisted && e.count > 0 && (best == null || e.median() < best.median())) {
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
