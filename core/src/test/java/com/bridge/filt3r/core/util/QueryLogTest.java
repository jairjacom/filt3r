package com.bridge.filt3r.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The DNS query log: each line keeps the asking app's name and package name (the key) when known. */
class QueryLogTest {

    @TempDir
    File dir;

    private QueryLog log() {
        return new QueryLog(new File(dir, "query.log"), () -> true);
    }

    @Test
    void appNameFollowsTheHostAfterATabAndSurvivesAReload() {
        File file = new File(dir, "q.txt");
        QueryLog log = new QueryLog(file, () -> true);
        log.add("example.org", false, "Chrome", null);
        log.add("ads.example", true, null, null);

        List<String> lines = new QueryLog(file, () -> true).lines();
        assertEquals(2, lines.size());
        QueryLog.Entry first = QueryLog.parse(lines.get(0));
        assertEquals("example.org", first.host);
        assertEquals("Chrome", first.app);
        assertFalse(first.blocked);
        QueryLog.Entry second = QueryLog.parse(lines.get(1));
        assertEquals("ads.example", second.host);
        assertNull(second.app);
        assertTrue(second.blocked);
    }

    @Test
    void anAppLabelCannotSplitOrCorruptTheLine() {
        QueryLog log = log();
        log.add("example.org", false, "Evil\nApp\tName\u202Egnp", null);

        List<String> lines = log.lines();
        assertEquals(1, lines.size());
        assertEquals("Evil App Name gnp", QueryLog.parse(lines.get(0)).app);
    }

    @Test
    void aVeryLongAppLabelIsCutAndAnEmptyOneIsDropped() {
        QueryLog log = log();
        log.add("a.example", false, "x".repeat(500), null);
        log.add("b.example", false, " \n ", null);

        List<String> lines = log.lines();
        assertEquals(64, QueryLog.parse(lines.get(0)).app.length());
        assertNull(QueryLog.parse(lines.get(1)).app);
    }

    @Test
    void aLineWithoutAnAppStillParsesAndGarbageDoesNot() {
        QueryLog.Entry e = QueryLog.parse("11-26 12:03:44 BLOCKED ads.example");
        assertEquals("11-26 12:03:44", e.time);
        assertEquals("ads.example", e.host);
        assertNull(e.app);
        assertNull(QueryLog.parse("11-26 12:03:44 MAYBE ads.example"));
        assertNull(QueryLog.parse("short"));
    }

    @Test
    void aQueryKeepsItsAppKeyAfterTheName() {
        QueryLog log = log();
        log.add("a.example", false, "Chat", "org.example.chat");
        QueryLog.Entry entry = QueryLog.parse(log.lines().get(0));
        assertEquals("Chat", entry.app);
        assertEquals("org.example.chat", entry.key);
    }

    @Test
    void aLineWrittenBeforeTheKeyFieldStillParsesWithNoKey() {
        QueryLog.Entry entry = QueryLog.parse("12-01 10:00:00 ALLOWED a.example\tChat");
        assertEquals("Chat", entry.app);
        assertNull(entry.key);
    }

    @Test
    void aKeyWithNoLabelStillRoundTrips() {
        QueryLog log = log();
        log.add("a.example", true, null, "org.example.chat");
        QueryLog.Entry entry = QueryLog.parse(log.lines().get(0));
        assertNull(entry.app);
        assertEquals("org.example.chat", entry.key);
    }

    @Test
    void removeAppsDropsOnlyThatAppsLinesAndRewritesTheFile() throws IOException {
        QueryLog log = log();
        log.add("a.example", false, "Chat", "org.example.chat");
        log.add("b.example", false, "Mail", "org.example.mail");
        log.removeApps(Set.of("org.example.chat"));
        assertEquals(1, log.lines().size());
        assertEquals("org.example.mail", QueryLog.parse(log.lines().get(0)).key);
        String onDisk = new String(Files.readAllBytes(new File(dir, "query.log").toPath()),
                StandardCharsets.UTF_8);
        assertFalse(onDisk.contains("org.example.chat"));
        assertTrue(onDisk.contains("org.example.mail"));
    }

    @Test
    void removeAppsWithNoMatchingKeyKeepsEverything() {
        QueryLog log = log();
        log.add("a.example", false, "Chat", "org.example.chat");
        log.removeApps(Set.of("org.example.unknown"));
        assertEquals(1, log.lines().size());
    }
}
