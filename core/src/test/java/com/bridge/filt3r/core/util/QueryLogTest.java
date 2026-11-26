package com.bridge.filt3r.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(lines.get(0).endsWith(" ALLOWED example.org\tChrome"));
        assertTrue(lines.get(1).endsWith(" BLOCKED ads.example"));
    }

    @Test
    void anAppLabelCannotSplitOrCorruptTheLine() {
        QueryLog log = new QueryLog(new File(dir, "q.txt"), () -> true);
        log.add("example.org", false, "Evil\nApp\tName");

        List<String> lines = log.lines();
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).endsWith(" ALLOWED example.org\tEvil App Name"));
    }
}
