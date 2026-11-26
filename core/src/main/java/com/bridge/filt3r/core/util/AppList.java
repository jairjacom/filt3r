package com.bridge.filt3r.core.util;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the Apps screen shows: the installed apps that pass the system switch and the search, each with
 * what the connection log says about it. Apps with log lines come first, newest first; the rest follow by
 * name. Lines are keyed by the first package Android reports for a user ID, so an app counts the lines of
 * every package that shares its user ID (the "group"), each line once.
 */
public final class AppList {

    private AppList() {
    }

    /** An installed package; {@code group} holds the packages that share its user ID, itself included. */
    public static final class App {
        public final String pkg;
        public final String label;
        public final boolean system;
        public final Set<String> group;

        public App(String pkg, String label, boolean system, Set<String> group) {
            this.pkg = pkg;
            this.label = label;
            this.system = system;
            this.group = group;
        }
    }

    /** One line of the screen. {@code lastTime} is the log's time text of the newest line, null when there is none. */
    public static final class Row {
        public final App app;
        public final int allowed;
        public final int blocked;
        public final String lastTime;
        public final int sharedWith;

        Row(App app, int allowed, int blocked, String lastTime) {
            this.app = app;
            this.allowed = allowed;
            this.blocked = blocked;
            this.lastTime = lastTime;
            this.sharedWith = app.group.size() - 1;
        }

        private boolean active() {
            return lastTime != null;
        }
    }

    /** What the log says about one app key. */
    private static final class Counts {
        int allowed;
        int blocked;
        String last; // "MM-dd HH:mm:ss", so the text order is the time order within a year
    }

    /**
     * @param showSystem false hides system apps
     * @param query shown only if it is part of the name or the package (any case); blank shows all
     */
    public static List<Row> build(List<App> apps, List<ConnectionLog.Entry> entries, boolean showSystem, String query) {
        Map<String, Counts> byKey = new HashMap<>();
        for (ConnectionLog.Entry e : entries) {
            if (e.appKey.isEmpty()) {
                continue;
            }
            Counts c = byKey.computeIfAbsent(e.appKey, k -> new Counts());
            if (e.blocked) {
                c.blocked += e.count;
            } else {
                c.allowed += e.count;
            }
            if (c.last == null || e.time.compareTo(c.last) > 0) {
                c.last = e.time;
            }
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<Row> rows = new ArrayList<>();
        for (App a : apps) {
            if ((a.system && !showSystem) || !matches(a, needle)) {
                continue;
            }
            int allowed = 0;
            int blocked = 0;
            String last = null;
            for (String pkg : a.group) {
                Counts c = byKey.get(pkg);
                if (c == null) {
                    continue;
                }
                allowed += c.allowed;
                blocked += c.blocked;
                if (last == null || c.last.compareTo(last) > 0) {
                    last = c.last;
                }
            }
            rows.add(new Row(a, allowed, blocked, last));
        }
        rows.sort(Comparator.<Row, Boolean>comparing(r -> !r.active())
                .thenComparing((Row r) -> r.lastTime == null ? "" : r.lastTime, Comparator.reverseOrder())
                .thenComparing((Row r) -> r.app.label.toLowerCase(Locale.ROOT))
                .thenComparing((Row r) -> r.app.pkg));
        return rows;
    }

    private static boolean matches(App a, String needle) {
        return needle.isEmpty() || a.label.toLowerCase(Locale.ROOT).contains(needle)
                || a.pkg.toLowerCase(Locale.ROOT).contains(needle);
    }
}
