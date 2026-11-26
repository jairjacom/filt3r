package com.bridge.filt3r.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The per-app TCP connection log: one line per flow, repeats merged, kept in a small private file. */
class ConnectionLogTest {

    private static final AppId CHROME = AppId.of(new String[] {"org.example.chrome"}, 10100, "Chrome", null);

    @TempDir
    File dir;

    private final AtomicLong now = new AtomicLong(0); // epoch 0 is "01-01 00:00:00" in UTC
    private final AtomicBoolean enabled = new AtomicBoolean(true);

    private ConnectionLog newLog() {
        return new ConnectionLog(new File(dir, "connlog.txt"), enabled::get, now::get, TimeZone.getTimeZone("UTC"));
    }

    @Test
    void anAllowedConnectionIsKeptAsOneLineThatParsesBack() {
        ConnectionLog log = newLog();
        log.add(CHROME, "example.org", 443, false, null);

        List<String> lines = log.lines();
        assertEquals(1, lines.size());
        ConnectionLog.Entry e = ConnectionLog.parse(lines.get(0));
        assertEquals("01-01 00:00:00", e.time);
        assertFalse(e.blocked);
        assertEquals("example.org", e.dest);
        assertEquals(443, e.port);
        assertEquals(1, e.count);
        assertNull(e.reason);
        assertEquals("org.example.chrome", e.appKey);
        assertEquals("Chrome", e.label);
    }

    @Test
    void aRepeatWithinThirtySecondsRaisesTheCountAndMovesTheLineToTheEnd() {
        ConnectionLog log = newLog();
        log.add(CHROME, "a.example", 443, false, null);
        now.set(10_000);
        log.add(CHROME, "b.example", 443, false, null);
        now.set(20_000);
        log.add(CHROME, "a.example", 443, false, null);

        List<String> lines = log.lines();
        assertEquals(2, lines.size());
        assertEquals("b.example", ConnectionLog.parse(lines.get(0)).dest);
        ConnectionLog.Entry a = ConnectionLog.parse(lines.get(1));
        assertEquals("a.example", a.dest);
        assertEquals(2, a.count);
        assertEquals("01-01 00:00:20", a.time);

        // the window runs from the last sighting, so steady traffic stays one line
        now.set(49_000);
        log.add(CHROME, "a.example", 443, false, null);
        assertEquals(3, ConnectionLog.parse(log.lines().get(1)).count);

        now.set(80_000); // 31 s after the last sighting
        log.add(CHROME, "a.example", 443, false, null);
        assertEquals(3, log.lines().size());
        assertEquals(1, ConnectionLog.parse(log.lines().get(2)).count);
    }

    @Test
    void flowsThatDifferInVerdictReasonAppOrPortAreNeverMerged() {
        AppId other = AppId.of(new String[] {"org.example.other"}, 10101, "Other", null);
        ConnectionLog log = newLog();
        log.add(CHROME, "a.example", 443, false, null);
        log.add(CHROME, "a.example", 443, true, "IP list");
        log.add(CHROME, "a.example", 443, true, "IPv6");
        log.add(other, "a.example", 443, false, null);
        log.add(CHROME, "a.example", 80, false, null);
        log.add(null, "a.example", 443, false, null);
        assertEquals(6, log.lines().size());
    }

    @Test
    void nothingIsKeptWhileTheSwitchIsOff() {
        enabled.set(false);
        ConnectionLog log = newLog();
        log.add(CHROME, "a.example", 443, false, null);
        assertEquals(0, log.lines().size());
        enabled.set(true);
        log.add(CHROME, "a.example", 443, false, null);
        assertEquals(1, log.lines().size());
    }

    @Test
    void onlyTheLastThousandLinesAreKept() {
        ConnectionLog log = newLog();
        for (int i = 0; i < 1005; i++) {
            log.add(CHROME, "host" + i + ".example", 443, false, null);
        }
        List<String> lines = log.lines();
        assertEquals(1000, lines.size());
        assertEquals("host5.example", ConnectionLog.parse(lines.get(0)).dest);
        assertEquals("host1004.example", ConnectionLog.parse(lines.get(999)).dest);
    }

    private List<String> fileLines() throws IOException {
        return Files.readAllLines(new File(dir, "connlog.txt").toPath(), StandardCharsets.UTF_8);
    }

    @Test
    void theFileIsRewrittenAtMostEveryFiveSecondsUnlessFlushed() throws IOException {
        ConnectionLog log = newLog();
        log.add(CHROME, "a.example", 443, false, null);
        assertEquals(1, fileLines().size());

        now.set(1_000);
        log.add(CHROME, "b.example", 443, false, null);
        assertEquals(1, fileLines().size()); // too soon: kept in memory only

        log.flush();
        assertEquals(2, fileLines().size());

        now.set(7_000); // 6 s after the flush
        log.add(CHROME, "c.example", 443, false, null);
        assertEquals(3, fileLines().size());
    }

    @Test
    void aNewLogReadsBackWhatTheOldOneSavedAndSkipsDamagedLines() throws IOException {
        ConnectionLog log = newLog();
        log.add(CHROME, "a.example", 443, false, null);
        log.add(CHROME, "a.example", 443, false, null);
        log.add(CHROME, "b.example", 443, true, "IP list");
        log.flush();
        List<String> saved = log.lines();
        Files.write(new File(dir, "connlog.txt").toPath(), "junk line\n".getBytes(StandardCharsets.UTF_8),
                StandardOpenOption.APPEND);

        assertEquals(saved, newLog().lines());
    }

    @Test
    void afterARestartTheSameFlowStartsANewLine() {
        ConnectionLog log = newLog();
        log.add(CHROME, "a.example", 443, false, null);
        log.flush();

        ConnectionLog restarted = newLog();
        restarted.add(CHROME, "a.example", 443, false, null);
        assertEquals(2, restarted.lines().size());
    }

    @Test
    void clearEmptiesTheLogAndTheFile() throws IOException {
        ConnectionLog log = newLog();
        log.add(CHROME, "a.example", 443, false, null);
        log.clear();
        assertEquals(0, log.lines().size());
        assertEquals(0, newLog().lines().size());
    }

    @Test
    void aHostNameOrReasonCannotBreakTheLineFormat() {
        ConnectionLog log = newLog();
        log.add(CHROME, "evil\n.example\t", 443, true, "IP\tlist\r\nx");

        List<String> lines = log.lines();
        assertEquals(1, lines.size());
        ConnectionLog.Entry e = ConnectionLog.parse(lines.get(0));
        assertEquals("evil .example", e.dest);
        assertEquals("IP list  x", e.reason);
    }

    @Test
    void removeAppsDropsOnlyThoseAppsLinesAndRewritesTheFile() throws IOException {
        AppId mail = AppId.of(new String[] {"org.example.mail"}, 10101, "Mail", null);
        ConnectionLog log = newLog();
        log.add(CHROME, "a.example", 443, false, null);
        log.add(mail, "b.example", 443, true, "IP blocklist");
        log.removeApps(java.util.Set.of("org.example.chrome"));

        List<String> lines = log.lines();
        assertEquals(1, lines.size());
        assertEquals("org.example.mail", ConnectionLog.parse(lines.get(0)).appKey);
        String onDisk = new String(Files.readAllBytes(new File(dir, "connlog.txt").toPath()), StandardCharsets.UTF_8);
        assertFalse(onDisk.contains("org.example.chrome"));
    }

    @Test
    void aFlowOfARemovedAppStartsAFreshLineInsteadOfMergingIntoAGoneOne() {
        ConnectionLog log = newLog();
        log.add(CHROME, "a.example", 443, false, null);
        log.removeApps(java.util.Set.of("org.example.chrome"));
        now.set(5_000);
        log.add(CHROME, "a.example", 443, false, null);

        List<String> lines = log.lines();
        assertEquals(1, lines.size());
        assertEquals(1, ConnectionLog.parse(lines.get(0)).count);
    }

    @Test
    void removeAppsWithNoMatchingKeyKeepsEverything() {
        ConnectionLog log = newLog();
        log.add(CHROME, "a.example", 443, false, null);
        log.removeApps(java.util.Set.of("org.example.unknown"));
        assertEquals(1, log.lines().size());
    }
}
