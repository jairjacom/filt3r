package com.bridge.filt3r.core.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * What the Firewall screen shows: the installed apps that pass the system switch and the search, A to Z by
 * name, each with what the connection log says about it. Lines are keyed by the first package Android
 * reports for a user ID, so an app counts the lines of every package that shares its user ID (the "group"),
 * each line once.
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

    /**
     * One line of the screen. {@code lastTime} is the log's time text of the newest line, null when there
     * is none; the newest is the one latest in the log, because the log keeps no year and the time text
     * alone cannot say which line is newer across New Year.
     */
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
    }

    /** What the log says about one app key. */
    private static final class Counts {
        int allowed;
        int blocked;
        int lastIndex; // place of the newest line in the log
        String last; // that line's time text
    }

    /**
     * @param system true lists only system apps (the System tab), false only the others (the Apps tab)
     * @param query shown only if it is part of the name or the package (any case); blank shows all
     */
    public static List<Row> build(List<App> apps, List<ConnectionLog.Entry> entries, boolean system, String query) {
        Map<String, Counts> byKey = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) { // oldest first, so a later line is a newer one
            ConnectionLog.Entry e = entries.get(i);
            if (e.appKey.isEmpty()) {
                continue;
            }
            Counts c = byKey.computeIfAbsent(e.appKey, k -> new Counts());
            if (e.blocked) {
                c.blocked += e.count;
            } else {
                c.allowed += e.count;
            }
            c.lastIndex = i;
            c.last = e.time;
        }
        String needle = query.trim().toLowerCase(Locale.ROOT);
        List<Row> rows = new ArrayList<>();
        for (App a : apps) {
            if (a.system != system || !matches(a, needle)) {
                continue;
            }
            int allowed = 0;
            int blocked = 0;
            String last = null;
            int order = -1;
            for (String pkg : a.group) {
                Counts c = byKey.get(pkg);
                if (c == null) {
                    continue;
                }
                allowed += c.allowed;
                blocked += c.blocked;
                if (c.lastIndex > order) {
                    order = c.lastIndex;
                    last = c.last;
                }
            }
            rows.add(new Row(a, allowed, blocked, last));
        }
        rows.sort(AppList::compare);
        return rows;
    }

    private static boolean matches(App a, String needle) {
        return needle.isEmpty() || a.label.toLowerCase(Locale.ROOT).contains(needle)
                || a.pkg.toLowerCase(Locale.ROOT).contains(needle);
    }

    /** A to Z by name (any case), then by package. */
    private static int compare(Row a, Row b) {
        int byName = a.app.label.compareToIgnoreCase(b.app.label);
        return byName != 0 ? byName : a.app.pkg.compareTo(b.app.pkg);
    }
}
