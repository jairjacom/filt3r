package com.bridge.filt3r.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The Firewall screen's app list: which apps are listed, what the log says about each, and in what order. */
class AppListTest {

    private static AppList.App app(String pkg, String label) {
        return new AppList.App(pkg, label, false, Set.of(pkg));
    }

    private static AppList.App systemApp(String pkg, String label) {
        return new AppList.App(pkg, label, true, Set.of(pkg));
    }

    /** A connection line as ConnectionLog writes it. */
    private static ConnectionLog.Entry entry(String key, boolean blocked, int count, String time) {
        String line = String.join("\t", time, blocked ? "BLOCKED" : "ALLOWED", "203.0.113.7:443", "x" + count,
                blocked ? "IP list" : "", key, "label");
        return ConnectionLog.parse(line);
    }

    private static List<String> packages(List<AppList.Row> rows) {
        List<String> out = new ArrayList<>();
        for (AppList.Row r : rows) {
            out.add(r.app.pkg);
        }
        return out;
    }

    @Test
    void everyLineCountsAsManyTimesAsItWasMerged() {
        List<AppList.Row> rows = AppList.build(List.of(app("a", "A")),
                List.of(entry("a", false, 3, "11-26 10:00:00"), entry("a", false, 1, "11-26 10:05:00"),
                        entry("a", true, 2, "11-26 10:07:00")),
                false, "");

        assertEquals(4, rows.get(0).allowed);
        assertEquals(2, rows.get(0).blocked);
        assertEquals("11-26 10:07:00", rows.get(0).lastTime);
    }

    @Test
    void anAppWithNoLinesHasNoSummary() {
        AppList.Row row = AppList.build(List.of(app("a", "A")), List.of(), false, "").get(0);

        assertEquals(0, row.allowed + row.blocked);
        assertNull(row.lastTime);
    }

    @Test
    void linesOfUnlistedOrUnknownOwnersAreIgnored() {
        List<AppList.Row> rows = AppList.build(List.of(app("a", "A")),
                List.of(entry("gone.app", false, 5, "11-26 10:00:00"), entry("uid:10234", false, 5, "11-26 10:00:00"),
                        entry("", false, 5, "11-26 10:00:00")),
                false, "");

        assertEquals(1, rows.size());
        assertEquals(0, rows.get(0).allowed);
    }

    @Test
    void appsAreSortedAToZByNameIgnoringCaseWhateverTheLogSays() {
        List<AppList.App> apps = Arrays.asList(app("z.quiet", "Zebra"), app("b.old", "Bee"),
                app("a.quiet", "apple"), app("c.new", "Cat"));
        List<AppList.Row> rows = AppList.build(apps,
                List.of(entry("b.old", false, 1, "11-26 09:00:00"), entry("c.new", false, 1, "11-26 11:00:00")),
                false, "");

        // apple sorts before Zebra although it is lower case
        assertEquals(List.of("a.quiet", "b.old", "c.new", "z.quiet"), packages(rows));
    }

    @Test
    void eachTabListsOnlyItsOwnKindOfApp() {
        List<AppList.App> apps = List.of(app("a", "A"), systemApp("sys.core", "AI Core"));
        List<ConnectionLog.Entry> lines = List.of(entry("sys.core", false, 1, "11-26 10:00:00"));

        assertEquals(List.of("a"), packages(AppList.build(apps, lines, false, "")));
        assertEquals(List.of("sys.core"), packages(AppList.build(apps, lines, true, "")));
    }

    @Test
    void aSharedUserIdGroupWithASystemPackageIsOnTheSystemTabWhole() {
        // an updated Play services is a normal app on its own but shares its user ID with a system package
        Set<String> group = Set.of("gms", "gsf");
        List<AppList.App> apps = List.of(new AppList.App("gms", "Play services", false, group),
                new AppList.App("gsf", "Services Framework", true, group), app("a", "A"));

        assertEquals(List.of("a"), packages(AppList.build(apps, List.of(), false, "")));
        assertEquals(List.of("gms", "gsf"), packages(AppList.build(apps, List.of(), true, "")));
    }

    @Test
    void aTabsSwitchesActOnItsOwnGroupsOnlyWhateverTheSearchSaysAndSkipExemptApps() {
        Set<String> mixed = Set.of("gms", "gsf");
        List<AppList.App> apps = List.of(app("a", "A"), app("exempt.app", "Calls"),
                new AppList.App("gms", "Play services", false, mixed),
                new AppList.App("gsf", "Services Framework", true, mixed), systemApp("sys.core", "AI Core"));

        assertEquals(List.of(Set.of("a")), AppList.tabGroups(apps, false, Set.of("exempt.app")));
        assertEquals(List.of(mixed, mixed, Set.of("sys.core")), AppList.tabGroups(apps, true, Set.of()));
        assertEquals(List.of(Set.of("a"), Set.of("exempt.app")), AppList.tabGroups(apps, false, Set.of()));
    }

    @Test
    void searchMatchesNameOrPackageIgnoringCaseAndSurroundingSpaces() {
        List<AppList.App> apps = List.of(app("org.example.chrome", "Browser"), app("com.other", "Chromium Notes"),
                app("net.mail", "Mail"));

        assertEquals(List.of("org.example.chrome", "com.other"), packages(AppList.build(apps, List.of(), false, " CHROM ")));
        assertEquals(List.of("net.mail"), packages(AppList.build(apps, List.of(), false, "mail")));
        assertTrue(AppList.build(apps, List.of(), false, "nothing").isEmpty());
    }

    @Test
    void theNewestLineIsTheOneLatestInTheLogEvenWhenTheDateWrapsAtNewYear() {
        // the log keeps no year, so "01-01" text sorts before "12-31" although it is newer
        List<AppList.Row> rows = AppList.build(List.of(app("a", "A")),
                List.of(entry("a", false, 1, "12-31 23:59:00"), entry("a", false, 1, "01-01 00:05:00")),
                false, "");

        assertEquals("01-01 00:05:00", rows.get(0).lastTime);
    }

    @Test
    void searchDoesNotBringBackHiddenSystemApps() {
        List<AppList.App> apps = List.of(systemApp("sys.core", "AI Core"));

        assertTrue(AppList.build(apps, List.of(), false, "core").isEmpty());
    }

    @Test
    void appsSharingAUserIdShareTheCountsOfTheirGroupAndSayHowManyShareIt() {
        Set<String> group = Set.of("android", "com.android.settings.intelligence");
        List<AppList.App> apps = List.of(new AppList.App("android", "Android System", true, group),
                new AppList.App("com.android.settings.intelligence", "Settings Suggestions", true, group),
                app("a", "A"));
        // the log keyed the flows by the first package Android returned for the uid
        List<AppList.Row> rows = AppList.build(apps,
                List.of(entry("android", false, 2, "11-26 10:00:00"),
                        entry("com.android.settings.intelligence", true, 1, "11-26 10:01:00")),
                true, "");

        for (AppList.Row r : rows) {
            if (r.app.pkg.equals("a")) {
                assertEquals(0, r.sharedWith);
                continue;
            }
            assertEquals(2, r.allowed);
            assertEquals(1, r.blocked);
            assertEquals(1, r.sharedWith);
        }
    }

    @Test
    void aSingleAppWithOwnUserIdSharesWithNobody() {
        assertEquals(0, AppList.build(List.of(app("a", "A")), List.of(), false, "").get(0).sharedWith);
    }
}
