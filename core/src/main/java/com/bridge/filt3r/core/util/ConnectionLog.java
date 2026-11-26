package com.bridge.filt3r.core.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * A plain-text log of TCP connections, one line per flow, tab separated:
 * "MM-dd HH:mm:ss, ALLOWED|BLOCKED, dest:port, xN, reason, app key, app label". An IPv6 destination
 * is written in brackets. A flow with the same app, destination, port, verdict and reason as a line
 * seen in the last {@value #MERGE_WINDOW_MS} ms is merged into that line. The last {@value #MAX_LINES}
 * lines are kept in memory and in one private file. A merge changes a line that is already there, so
 * the file is rewritten whole (to a temp file, then renamed) at most every {@value #WRITE_INTERVAL_MS}
 * ms; call {@link #flush()} when the app stops or the log is shown. Nothing is kept while the enabled
 * switch is off.
 */
public final class ConnectionLog {

    public static final int MAX_LINES = 1000;
    private static final long MERGE_WINDOW_MS = 30_000;
    private static final long WRITE_INTERVAL_MS = 5_000;

    private final File file;
    private final BooleanSupplier enabled;
    private final LongSupplier clock;
    private final SimpleDateFormat time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.ROOT);
    private final List<Row> rows = new ArrayList<>(); // oldest first
    private final Map<String, Row> latest = new HashMap<>(); // merge key -> its newest live row
    private long lastWriteMs = Long.MIN_VALUE / 2;
    private boolean dirty;

    /**
     * @param enabled asked on every connection, so the switch takes effect at once
     * @param clock milliseconds since the epoch
     */
    public ConnectionLog(File file, BooleanSupplier enabled, LongSupplier clock, TimeZone zone) {
        this.file = file;
        this.enabled = enabled;
        this.clock = clock;
        time.setTimeZone(zone);
        load();
    }

    /** One flow, or several merged. A row read back from the file keeps its saved time and never merges. */
    private static final class Row {
        final String key;
        final String verdictAndTarget; // "ALLOWED<TAB>dest:port"
        final String reason;
        final String app; // "key<TAB>label"
        final String savedTime; // null for a row of this run
        long lastMs;
        int count = 1;

        Row(String key, String verdictAndTarget, String reason, String app, long lastMs, String savedTime) {
            this.key = key;
            this.verdictAndTarget = verdictAndTarget;
            this.reason = reason;
            this.app = app;
            this.lastMs = lastMs;
            this.savedTime = savedTime;
        }
    }

    /** One parsed log line. */
    public static final class Entry {
        public final String time;
        public final boolean blocked;
        public final String dest;
        public final int port;
        public final int count;
        public final String reason; // null when the line has none
        public final String appKey; // empty when the owner was unknown
        public final String label; // empty when the owner was unknown

        Entry(String time, boolean blocked, String dest, int port, int count, String reason,
                String appKey, String label) {
            this.time = time;
            this.blocked = blocked;
            this.dest = dest;
            this.port = port;
            this.count = count;
            this.reason = reason;
            this.appKey = appKey;
            this.label = label;
        }
    }

    /** A line written by {@link #add} -> Entry; null for a line that does not fit. */
    public static Entry parse(String line) {
        String[] f = line.split("\t", -1);
        if (f.length != 7 || !(f[1].equals("ALLOWED") || f[1].equals("BLOCKED")) || !f[3].startsWith("x")) {
            return null;
        }
        int colon = f[2].lastIndexOf(':');
        if (colon < 1) {
            return null;
        }
        try {
            String dest = f[2].substring(0, colon);
            if (dest.startsWith("[") && dest.endsWith("]")) {
                dest = dest.substring(1, dest.length() - 1);
            }
            return new Entry(f[0], f[1].equals("BLOCKED"), dest, Integer.parseInt(f[2].substring(colon + 1)),
                    Integer.parseInt(f[3].substring(1)), f[4].isEmpty() ? null : f[4], f[5], f[6]);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Called for every TCP connection that reaches the bridge.
     *
     * @param app the owner, or null when unknown
     * @param dest a host name or an IP literal
     * @param reason why it was refused; null when allowed
     */
    public synchronized void add(AppId app, String dest, int port, boolean blocked, String reason) {
        if (!enabled.getAsBoolean()) {
            return;
        }
        long nowMs = clock.getAsLong();
        String shown = dest.indexOf(':') >= 0 ? "[" + clean(dest) + "]" : clean(dest);
        String verdictAndTarget = (blocked ? "BLOCKED" : "ALLOWED") + "\t" + shown + ":" + port;
        String why = reason == null ? "" : clean(reason);
        String who = app == null ? "\t" : app.key() + "\t" + app.label();
        String key = verdictAndTarget + "\t" + why + "\t" + who;
        Row row = latest.get(key);
        if (row != null && nowMs - row.lastMs <= MERGE_WINDOW_MS) {
            rows.remove(row);
            row.count++;
            row.lastMs = nowMs;
        } else {
            row = new Row(key, verdictAndTarget, why, who, nowMs, null);
            latest.put(key, row);
        }
        rows.add(row);
        while (rows.size() > MAX_LINES) {
            Row dropped = rows.remove(0);
            latest.remove(dropped.key, dropped); // only if no newer row owns the key
        }
        dirty = true;
        if (nowMs - lastWriteMs >= WRITE_INTERVAL_MS) {
            write(nowMs);
        }
    }

    /** The kept lines, oldest first. */
    public synchronized List<String> lines() {
        List<String> out = new ArrayList<>(rows.size());
        for (Row r : rows) {
            out.add(line(r));
        }
        return out;
    }

    /** Writes the file now if a change is still waiting for the next rewrite. */
    public synchronized void flush() {
        if (dirty) {
            write(clock.getAsLong());
        }
    }

    public synchronized void clear() {
        rows.clear();
        latest.clear();
        dirty = false;
        file.delete();
    }

    /** Deletes every line of the apps with these keys (package names) and rewrites the file. */
    public synchronized void removeApps(java.util.Set<String> keys) {
        boolean removed = rows.removeIf(r -> keys.contains(r.app.substring(0, r.app.indexOf('\t'))));
        if (removed) {
            latest.values().removeIf(r -> !rows.contains(r));
            write(clock.getAsLong());
        }
    }

    private String line(Row r) {
        String t = r.savedTime != null ? r.savedTime : time.format(new Date(r.lastMs));
        return t + "\t" + r.verdictAndTarget + "\tx" + r.count + "\t" + r.reason + "\t" + r.app;
    }

    private void write(long nowMs) {
        lastWriteMs = nowMs; // a failing disk is retried at the next interval, not on every connection
        File tmp = new File(file.getPath() + ".tmp");
        try {
            StringBuilder out = new StringBuilder();
            for (Row r : rows) {
                out.append(line(r)).append('\n');
            }
            Files.write(tmp.toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            dirty = false;
        } catch (IOException e) {
            tmp.delete(); // the lines stay in memory
        }
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        try {
            for (String saved : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                Entry e = parse(saved);
                if (e == null) {
                    continue;
                }
                String[] f = saved.split("\t", -1);
                Row row = new Row(saved, f[1] + "\t" + f[2], f[4], f[5] + "\t" + f[6], 0, f[0]);
                row.count = e.count;
                rows.add(row);
            }
        } catch (IOException e) {
            rows.clear(); // an unreadable file starts a fresh log
        }
        while (rows.size() > MAX_LINES) {
            rows.remove(0);
        }
    }

    /** Names and reasons come from outside: control and format characters become spaces so a line stays one line of seven fields. */
    private static String clean(String text) {
        return text.replaceAll("[\\p{Cc}\\p{Cf}]", " ").trim();
    }
}
