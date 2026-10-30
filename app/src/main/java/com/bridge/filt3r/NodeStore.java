package com.bridge.filt3r;

import com.bridge.filt3r.core.dns.NameResolver;
import com.bridge.filt3r.core.http.HttpsFetcher;
import com.bridge.filt3r.core.shadowsocks.ShadowsocksNode;
import com.bridge.filt3r.core.subscription.HostNameResolver;
import com.bridge.filt3r.core.subscription.SubscriptionParser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * App-private files for the node list. All files live in files/ and hold passwords, so they stay
 * private and are never logged.
 * <ul>
 * <li>nodes.txt: the cached nodes, one ss:// URL per line (written after each subscription update).</li>
 * <li>subscriptions.txt: up to 3 subscription URLs, one per line. Temporary until the Settings
 * screen exists (Step 6).</li>
 * <li>node.txt: one hand-placed ss:// node for development. Temporary, like the line above.</li>
 * </ul>
 */
final class NodeStore {

    static final long STALE_MS = 24L * 60 * 60 * 1000;
    private static final String CACHE = "nodes.txt";
    private static final String SUBSCRIPTIONS = "subscriptions.txt";
    private static final String DEV_NODE = "node.txt";
    private static final int MAX_SUBSCRIPTIONS = 3;

    private final File dir;

    NodeStore(File filesDir) {
        this.dir = filesDir;
    }

    /** The cached nodes plus the development node, without duplicates. */
    List<ShadowsocksNode> load() {
        Map<String, ShadowsocksNode> all = new LinkedHashMap<>();
        for (String line : lines(new File(dir, CACHE))) {
            add(all, line);
        }
        for (String line : lines(new File(dir, DEV_NODE))) {
            add(all, line);
        }
        return new ArrayList<>(all.values());
    }

    /** True if there is a subscription to read and the cache is missing or older than a day. */
    boolean needsUpdate() {
        File cache = new File(dir, CACHE);
        return !subscriptionUrls().isEmpty()
                && (!cache.isFile() || System.currentTimeMillis() - cache.lastModified() > STALE_MS);
    }

    boolean hasSubscriptions() {
        return !subscriptionUrls().isEmpty();
    }

    /**
     * Downloads every subscription, turns host names into IPs and saves the result as the cache.
     *
     * @return the new node list (cache content, not including the development node)
     * @throws IOException if no subscription could be fetched and read
     */
    List<ShadowsocksNode> update(HttpsFetcher fetcher, NameResolver names) throws IOException {
        Map<String, ShadowsocksNode> all = new LinkedHashMap<>();
        IOException last = null;
        int ok = 0;
        for (String url : subscriptionUrls()) {
            try {
                for (ShadowsocksNode n : SubscriptionParser.parse(fetcher.get(url)).nodes) {
                    all.putIfAbsent(n.host + ":" + n.port, n);
                }
                ok++;
            } catch (IOException | IllegalArgumentException e) {
                last = e instanceof IOException ? (IOException) e : new IOException(e.getMessage());
            }
        }
        if (ok == 0) {
            throw last != null ? last : new IOException("no subscription configured");
        }
        List<ShadowsocksNode> resolved = HostNameResolver.resolve(new ArrayList<>(all.values()), names);
        if (resolved.isEmpty()) {
            throw new IOException("subscriptions held no usable node");
        }
        save(resolved);
        return resolved;
    }

    private List<String> subscriptionUrls() {
        List<String> out = new ArrayList<>();
        for (String line : lines(new File(dir, SUBSCRIPTIONS))) {
            if (out.size() < MAX_SUBSCRIPTIONS) {
                out.add(line);
            }
        }
        return out;
    }

    private void save(List<ShadowsocksNode> nodes) throws IOException {
        StringBuilder text = new StringBuilder();
        for (ShadowsocksNode n : nodes) {
            text.append(n.toUri()).append('\n');
        }
        File tmp = new File(dir, CACHE + ".tmp");
        Files.write(tmp.toPath(), text.toString().getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), new File(dir, CACHE).toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void add(Map<String, ShadowsocksNode> into, String line) {
        try {
            ShadowsocksNode n = ShadowsocksNode.parse(line);
            into.putIfAbsent(n.host + ":" + n.port, n);
        } catch (IllegalArgumentException e) {
            // a bad line is skipped
        }
    }

    private static List<String> lines(File f) {
        List<String> out = new ArrayList<>();
        if (!f.isFile()) {
            return out;
        }
        try {
            for (String l : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                String t = l.trim();
                if (!t.isEmpty() && !t.startsWith("#")) {
                    out.add(t);
                }
            }
        } catch (IOException e) {
            // unreadable file: treated as empty
        }
        return out;
    }
}
