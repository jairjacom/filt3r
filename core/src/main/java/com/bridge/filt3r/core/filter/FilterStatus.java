package com.bridge.filt3r.core.filter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * What the filter-list download is doing and how the last one ended, for the screens. The filter
 * manager writes it; any thread may read a {@link Snapshot}. It lives in memory only.
 */
public final class FilterStatus {

    public enum State { NONE, DOWNLOADING, OK, FAILED }

    /** An immutable copy of the status. */
    public static final class Snapshot {
        public final State state;
        /** Lists finished and lists in this download (DOWNLOADING only). */
        public final int done;
        public final int total;
        /**
         * Entries read from all lists, and the number left after removing duplicates (-1 if unknown,
         * for lists loaded from an older download). Time (ms since the epoch) of the lists in use; 0 if none.
         */
        public final long entries;
        public final long unique;
        public final long updatedMs;
        /** Lists of the last download that could not be read (OK with failedLists > 0 means a partial update). */
        public final int failedLists;
        /**
         * FAILED, or OK with failed lists: what went wrong (no full URL: it may contain a token).
         * Retry time is when the next try is due (0 if none).
         */
        public final String error;
        public final long retryAtMs;
        /** One line per list of the last download: "host: 12,345 entries" or "host: failed (reason)". */
        public final List<String> listLines;

        Snapshot(State state, int done, int total, long entries, long unique, long updatedMs, int failedLists,
                String error, long retryAtMs, List<String> listLines) {
            this.state = state;
            this.done = done;
            this.total = total;
            this.entries = entries;
            this.unique = unique;
            this.updatedMs = updatedMs;
            this.failedLists = failedLists;
            this.error = error;
            this.retryAtMs = retryAtMs;
            this.listLines = listLines;
        }
    }

    private static State state = State.NONE;
    private static int done;
    private static int total;
    private static long entries;
    private static long unique = -1;
    private static long updatedMs;
    private static int failedLists;
    private static String error = "";
    private static long retryAtMs;
    private static List<String> listLines = new ArrayList<>();
    private static List<String> pendingLines = new ArrayList<>();
    private static int pendingFailed;
    private static String pendingError = "";

    private FilterStatus() {
    }

    public static synchronized Snapshot get() {
        return new Snapshot(state, done, total, entries, unique, updatedMs, failedLists, error, retryAtMs,
                new ArrayList<>(listLines));
    }

    static synchronized void downloading(int lists) {
        state = State.DOWNLOADING;
        done = 0;
        total = lists;
        error = "";
        retryAtMs = 0;
        pendingLines = new ArrayList<>();
        pendingFailed = 0;
        pendingError = "";
    }

    static synchronized void listRead(String host, long count) {
        pendingLines.add(host + ": " + String.format(java.util.Locale.US, "%,d", count) + " entries");
        done++;
    }

    static synchronized void listFailed(String host, String reason) {
        pendingLines.add(host + ": failed (" + reason + ")");
        if (pendingFailed++ == 0) {
            pendingError = host + ": " + reason;
        }
        done++;
    }

    static synchronized int failedInThisDownload() {
        return pendingFailed;
    }

    /** Every list of the download was read, or at least one was; failed ones are reported with the result. */
    static synchronized void ok(long rawCount, long modifiedMs) {
        state = State.OK;
        entries = rawCount;
        unique = -1; // known once the filter has built its index (setUnique)
        updatedMs = modifiedMs;
        failedLists = pendingFailed;
        error = pendingError;
        retryAtMs = 0;
        listLines = pendingLines;
    }

    /** The filter removed duplicates while building its index and kept this many names. */
    static synchronized void setUnique(long count) {
        unique = count;
    }

    static synchronized void failed(String message) {
        state = State.FAILED;
        error = message;
    }

    static synchronized boolean isFailed() {
        return state == State.FAILED;
    }

    static synchronized void retryAt(long ms) {
        retryAtMs = ms;
    }

    /** The download was stopped on purpose: back to what is loaded. */
    static synchronized void aborted() {
        state = updatedMs > 0 ? State.OK : State.NONE;
    }

    /**
     * Reads the list that is already on disk (the ".DLD_CNT" file: entry count, modification time).
     */
    static synchronized void loadedFromDisk(File info) {
        if (state != State.NONE || !info.isFile()) {
            return;
        }
        try {
            String[] lines = new String(Files.readAllBytes(info.toPath()), StandardCharsets.UTF_8).split("\n");
            entries = Long.parseLong(lines[0].trim());
            unique = entries; // the info file is rewritten with the unique count once the index is built
            updatedMs = info.lastModified();
            state = State.OK;
        } catch (Exception e) {
            // unreadable info file: the status stays NONE
        }
    }
}
