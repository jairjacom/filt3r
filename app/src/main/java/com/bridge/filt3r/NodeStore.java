package com.bridge.filt3r;

import com.bridge.filt3r.core.dns.NameResolver;
import com.bridge.filt3r.core.http.HttpsFetcher;
import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;
import com.bridge.filt3r.core.nodes.NodeManager;
import com.bridge.filt3r.core.subscription.HostNameResolver;
import com.bridge.filt3r.core.subscription.SubscriptionParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The node cache, one file per subscription slot (files/nodes_N.txt). The file holds the ss:// URLs
 * after a "#url" line naming the subscription it came from; its modification time is the time of
 * the last successful download. Files hold passwords, so they stay in private storage and are
 * never logged. A cache whose "#url" line no longer matches the slot's URL is ignored.
 */
final class NodeStore {

    private static final String URL_MARK = "#url ";

    private final File dir;
    private final List<Settings.Subscription> subscriptions;
    private final java.util.Set<String> hidden;

    NodeStore(File filesDir, List<Settings.Subscription> subscriptions, java.util.Set<String> hidden) {
        this.dir = filesDir;
        this.subscriptions = subscriptions;
        this.hidden = hidden;
    }

    boolean hasSubscriptions() {
        return !subscriptions.isEmpty();
    }

    /**
     * The saved nodes of the user first, then the cached nodes of all subscriptions, without duplicates:
     * a node the user added owns its server and port, even if a subscription lists it too, so it shows as
     * "Added by you" and sits at the top; otherwise the first subscription to list a node owns it.
     */
    List<ShadowsocksNode> load() {
        Map<String, ShadowsocksNode> all = new LinkedHashMap<>();
        Map<String, Integer> owner = new HashMap<>();
        for (ShadowsocksNode n : ManualNodes.read(dir)) {
            if (all.putIfAbsent(n.host + ":" + n.port, n) == null) {
                owner.put(NodeManager.keyOf(n), ManualNodes.SOURCE);
            }
        }
        for (Settings.Subscription s : subscriptions) {
            for (String line : cachedLines(s)) {
                try {
                    ShadowsocksNode n = ShadowsocksNode.parse(line);
                    if (hidden.contains(NodeManager.keyOf(n))) {
                        continue;
                    }
                    if (all.putIfAbsent(n.host + ":" + n.port, n) == null) {
                        owner.put(NodeManager.keyOf(n), s.slot);
                    }
                } catch (IllegalArgumentException e) {
                    // a bad line is skipped
                }
            }
        }
        sources = owner;
        sourceKinds = new java.util.HashSet<>(owner.values()).size();
        return new ArrayList<>(all.values());
    }

    private static volatile Map<String, Integer> sources = new HashMap<>();
    private static volatile int sourceKinds;

    /** The subscription slot (0-2) that supplied the node, or -1 if unknown. */
    static int sourceOf(ShadowsocksNode n) {
        Integer slot = sources.get(NodeManager.keyOf(n));
        return slot == null ? -1 : slot;
    }

    /** How many different subscriptions supply a node in the current list. */
    static int sourceCount() {
        return sourceKinds;
    }

    /** When the slot's cache was last downloaded (wall-clock ms), or 0 if it has none. */
    static long updatedAt(File filesDir, int slot) {
        File f = new File(filesDir, "nodes_" + slot + ".txt");
        return f.isFile() ? f.lastModified() : 0;
    }

    private static final java.util.Map<Integer, String> ERRORS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Why the slot's last download failed (a short text without addresses), or null if it did not fail. */
    static String lastError(int slot) {
        return ERRORS.get(slot);
    }

    /** Forgets a failure, for a slot whose address changed. */
    static void clearError(int slot) {
        ERRORS.remove(slot);
    }

    private static String shorten(Exception e) {
        String text = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        text = text.replaceAll("(?i)https?://\\S+", "<address>").trim();
        return text.length() > 80 ? text.substring(0, 80) + "..." : text;
    }

    /** How many nodes the slot's cache holds (0 if it has none). */
    static int cachedCount(File filesDir, int slot) {
        File f = new File(filesDir, "nodes_" + slot + ".txt");
        if (!f.isFile()) {
            return 0;
        }
        int count = 0;
        try {
            for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                if (!line.trim().isEmpty() && !line.startsWith("#")) {
                    count++;
                }
            }
        } catch (IOException e) {
            return 0;
        }
        return count;
    }

    /** True if a subscription has no cache yet or its cache is older than its update interval. */
    boolean anyDue() {
        for (Settings.Subscription s : subscriptions) {
            if (isDue(s)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Downloads subscriptions, turns host names into IPs and saves each result as that slot's cache.
     * A subscription that fails keeps its old cache.
     *
     * @param onlyDue true to skip subscriptions that are not due yet
     * @throws IOException if every attempted subscription failed
     */
    synchronized void update(HttpsFetcher fetcher, NameResolver names, boolean onlyDue) throws IOException {
        IOException last = null;
        int attempted = 0;
        int ok = 0;
        for (Settings.Subscription s : subscriptions) {
            if (onlyDue && !isDue(s)) {
                continue;
            }
            attempted++;
            try {
                updateOne(s, fetcher, names);
                ok++;
            } catch (IOException e) {
                last = e;
            }
        }
        if (attempted > 0 && ok == 0) {
            throw last;
        }
    }

    /**
     * Downloads one subscription now, whatever its interval. {@code s} need not be one of this store's
     * subscriptions: the cache file is chosen by {@code s.slot}, its {@code #url} line by {@code s.url}.
     * A failure keeps the old cache.
     *
     * @throws IOException if the download, the parsing or the name lookup failed, or no usable node came back
     */
    synchronized void updateOne(Settings.Subscription s, HttpsFetcher fetcher, NameResolver names) throws IOException {
        try {
            List<ShadowsocksNode> nodes = SubscriptionParser.parse(fetcher.get(s.url)).nodes;
            List<ShadowsocksNode> resolved = HostNameResolver.resolve(nodes, names);
            if (resolved.isEmpty()) {
                throw new IOException("subscription held no usable node");
            }
            save(s, resolved);
            ERRORS.remove(s.slot);
        } catch (IOException | IllegalArgumentException e) {
            ERRORS.put(s.slot, shorten(e));
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage());
        }
    }

    /** True if this store holds a subscription with the same slot and address. */
    boolean supplies(Settings.Subscription s) {
        for (Settings.Subscription m : subscriptions) {
            if (m.slot == s.slot && m.url.equals(s.url)) {
                return true;
            }
        }
        return false;
    }

    /** True if the last download of any of this store's subscriptions failed. */
    boolean anyFailed() {
        for (Settings.Subscription m : subscriptions) {
            if (ERRORS.containsKey(m.slot)) {
                return true;
            }
        }
        return false;
    }

    private boolean isDue(Settings.Subscription s) {
        File f = file(s);
        return cachedLines(s).isEmpty()
                || System.currentTimeMillis() - f.lastModified() > s.intervalHours * 3_600_000L;
    }

    private File file(Settings.Subscription s) {
        return new File(dir, "nodes_" + s.slot + ".txt");
    }

    private void save(Settings.Subscription s, List<ShadowsocksNode> nodes) throws IOException {
        StringBuilder text = new StringBuilder(URL_MARK).append(s.url).append('\n');
        for (ShadowsocksNode n : nodes) {
            text.append(n.toUri()).append('\n');
        }
        File tmp = new File(dir, "nodes_" + s.slot + ".tmp");
        Files.write(tmp.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), file(s).toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    /** The ss:// lines of the slot's cache, or none if there is no cache or it belongs to another URL. */
    private List<String> cachedLines(Settings.Subscription s) {
        List<String> out = new ArrayList<>();
        File f = file(s);
        if (!f.isFile()) {
            return out;
        }
        try {
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            if (lines.isEmpty() || !lines.get(0).equals(URL_MARK + s.url)) {
                return out;
            }
            for (String l : lines.subList(1, lines.size())) {
                if (!l.trim().isEmpty()) {
                    out.add(l.trim());
                }
            }
        } catch (IOException e) {
            // unreadable file: treated as no cache
        }
        return out;
    }
}
