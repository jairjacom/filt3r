package com.bridge.filt3r.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The DNS query log: each line keeps the asking app's package name (the key) beside its label. */
class QueryLogTest {

    @TempDir
    File dir;

    private QueryLog log() {
        return new QueryLog(new File(dir, "query.log"), () -> true);
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
