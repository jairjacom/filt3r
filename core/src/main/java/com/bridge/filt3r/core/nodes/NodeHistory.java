package com.bridge.filt3r.core.nodes;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * A small on-device log of which node carried traffic, kept for {@link #KEEP_MS}. One line per
 * event in a tab separated file: {@code <ms>\tS\t<key>\t<C|F>} starts a span on a node (C =
 * connect, F = failover), {@code <ms>\tE} ends the open span. The file is loaded on first use and
 * appended to; it is rewritten only when old spans are dropped. IO errors are swallowed because
 * the history is only a convenience.
 */
public final class NodeHistory {

    public static final long KEEP_MS = 7L * 24 * 3600 * 1000;
    private static final int MAX_LINES = 2000;

    /** One stretch of time on one node; {@code end} is -1 while the span is open. */
    private static final class Span {
        final long start;
        long end = -1;
        final String key;
        final boolean failover;

        Span(long start, String key, boolean failover) {
            this.start = start;
            this.key = key;
            this.failover = failover;
        }

        int lines() {
            return end >= 0 ? 2 : 1;
        }
    }

    private final File file;
    private final LongSupplier clock;
    private final List<Span> spans = new ArrayList<>();
    private boolean loaded;
    private long lastMs;

    public NodeHistory(File file, LongSupplier clock) {
        this.file = file;
        this.clock = clock;
    }

    /** A node starts carrying traffic (tunnel start or failover). Ends the span of the previous node. failover=true when it replaced a node that died. */
    public synchronized void recordStart(String nodeKey, boolean failover) {
        if (nodeKey == null || nodeKey.isEmpty() || nodeKey.indexOf('\t') >= 0
                || nodeKey.indexOf('\n') >= 0 || nodeKey.indexOf('\r') >= 0) {
            return;
        }
        load();
        Span open = open();
        if (open != null && open.key.equals(nodeKey)) {
            return;
        }
        long now = now();
        StringBuilder pending = new StringBuilder();
        if (open != null) {
            open.end = now;
            pending.append(now).append("\tE\n");
        }
        spans.add(new Span(now, nodeKey, failover));
        pending.append(now).append("\tS\t").append(nodeKey).append('\t').append(failover ? 'F' : 'C').append('\n');
        lastMs = now;
        commit(pending, prune(now));
    }

    /** The tunnel stopped: ends the current span. No-op if no span is open. */
    public synchronized void recordStop() {
        load();
        Span open = open();
        if (open == null) {
            return;
        }
        long now = now();
        open.end = now;
        lastMs = now;
        commit(new StringBuilder().append(now).append("\tE\n"), prune(now));
    }

    public synchronized Stats stats(String nodeKey) {
        load();
        long now = now();
        long cutoff = now - KEEP_MS;
        int connections = 0;
        long lastUsed = 0;
        long total = 0;
        for (Span s : spans) {
            if (!s.key.equals(nodeKey)) {
                continue;
            }
            long end = s.end >= 0 ? s.end : now;
            if (end < cutoff) {
                continue;
            }
            total += end - Math.max(s.start, cutoff);
            if (s.start >= cutoff) {
                connections++;
            }
            lastUsed = Math.max(lastUsed, end);
        }
        return new Stats(connections, lastUsed, total);
    }

    public synchronized Summary summary() {
        load();
        long cutoff = now() - KEEP_MS;
        int connections = 0;
        int failovers = 0;
        for (Span s : spans) {
            if (s.start >= cutoff) {
                connections++;
                if (s.failover) {
                    failovers++;
                }
            }
        }
        return new Summary(connections, failovers);
    }

    /** Deletes the file and forgets everything. */
    public synchronized void clear() {
        spans.clear();
        loaded = true;
        lastMs = 0;
        file.delete();
        new File(file.getPath() + ".tmp").delete();
    }

    private long now() {
        return Math.max(clock.getAsLong(), lastMs);
    }

    private Span open() {
        if (spans.isEmpty()) {
            return null;
        }
        Span last = spans.get(spans.size() - 1);
        return last.end < 0 ? last : null;
    }

    /** Reads the file once. A span left open by a crash is closed at the last event time. */
    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        String text = "";
        try {
            if (file.isFile()) {
                text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            return;
        }
        for (String line : text.split("\n")) {
            replay(line);
        }
        StringBuilder pending = new StringBuilder();
        Span open = open();
        if (open != null) {
            open.end = open.start;
            pending.append(open.start).append("\tE\n");
        }
        boolean dropped = prune(now());
        if (dropped || pending.length() > 0) {
            commit(pending, dropped);
        }
    }

    /** Applies one file line to the in-memory spans; malformed or out-of-order lines are ignored. */
    private void replay(String line) {
        String[] p = line.split("\t", -1);
        if (p.length < 2) {
            return;
        }
        long ms;
        try {
            ms = Long.parseLong(p[0]);
        } catch (NumberFormatException e) {
            return;
        }
        if (ms < lastMs) {
            return;
        }
        Span open = open();
        if (p.length == 2 && p[1].equals("E")) {
            if (open != null) {
                open.end = ms;
                lastMs = ms;
            }
        } else if (p.length == 4 && p[1].equals("S") && !p[2].isEmpty()
                && (p[3].equals("C") || p[3].equals("F"))) {
            if (open != null) {
                open.end = ms;
            }
            spans.add(new Span(ms, p[2], p[3].equals("F")));
            lastMs = ms;
        }
    }

    /** Drops spans that ended before the cutoff and the oldest ones beyond the line cap. Returns true if any went. */
    private boolean prune(long now) {
        long cutoff = now - KEEP_MS;
        boolean dropped = false;
        for (Iterator<Span> it = spans.iterator(); it.hasNext();) {
            Span s = it.next();
            if (s.end >= 0 && s.end < cutoff) {
                it.remove();
                dropped = true;
            }
        }
        int lines = 0;
        for (Span s : spans) {
            lines += s.lines();
        }
        while (lines > MAX_LINES && !spans.isEmpty()) {
            lines -= spans.remove(0).lines();
            dropped = true;
        }
        return dropped;
    }

    /** Appends the new lines, or rewrites the whole file if spans were dropped. */
    private void commit(StringBuilder pending, boolean rewrite) {
        try {
            File dir = file.getAbsoluteFile().getParentFile();
            if (dir != null && !dir.isDirectory()) {
                dir.mkdirs();
            }
            if (rewrite) {
                StringBuilder all = new StringBuilder();
                for (Span s : spans) {
                    all.append(s.start).append("\tS\t").append(s.key).append('\t').append(s.failover ? 'F' : 'C').append('\n');
                    if (s.end >= 0) {
                        all.append(s.end).append("\tE\n");
                    }
                }
                File tmp = new File(file.getPath() + ".tmp");
                Files.write(tmp.toPath(), all.toString().getBytes(StandardCharsets.UTF_8));
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.write(file.toPath(), pending.toString().getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException | RuntimeException e) {
            // the history is a convenience; keep going without it
        }
    }

    public static final class Stats {
        /** Times this node was started in the last 7 days. */
        public final int connections;
        /** When this node last carried traffic (an open span counts as now); 0 if never. */
        public final long lastUsedMs;
        /** Time on this node in the last 7 days; an open span counts up to now. */
        public final long totalMs;

        private Stats(int connections, long lastUsedMs, long totalMs) {
            this.connections = connections;
            this.lastUsedMs = lastUsedMs;
            this.totalMs = totalMs;
        }
    }

    public static final class Summary {
        public final int connections;
        public final int failovers;

        private Summary(int connections, int failovers) {
            this.connections = connections;
            this.failovers = failovers;
        }
    }
}
