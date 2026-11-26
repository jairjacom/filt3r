package com.bridge.filt3r.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class QueryLogTest {

    @TempDir
    File dir;

    @Test
    void appNameFollowsTheHostAfterATabAndSurvivesAReload() {
        File file = new File(dir, "q.txt");
        QueryLog log = new QueryLog(file, () -> true);
        log.add("example.org", false, "Chrome");
        log.add("ads.example", true, null);

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
        QueryLog log = new QueryLog(new File(dir, "q.txt"), () -> true);
        log.add("example.org", false, "Evil\nApp\tName\u202Egnp");

        List<String> lines = log.lines();
        assertEquals(1, lines.size());
        assertEquals("Evil App Name gnp", QueryLog.parse(lines.get(0)).app);
    }

    @Test
    void aVeryLongAppLabelIsCutAndAnEmptyOneIsDropped() {
        QueryLog log = new QueryLog(new File(dir, "q.txt"), () -> true);
        log.add("a.example", false, "x".repeat(500));
        log.add("b.example", false, " \n ");

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
}
