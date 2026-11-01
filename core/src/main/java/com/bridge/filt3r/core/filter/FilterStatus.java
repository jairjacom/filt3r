package com.bridge.filt3r.core.filter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

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
        /** Entries and time (ms since the epoch) of the lists in use; 0 if none were loaded. */
        public final long entries;
        public final long updatedMs;
        /** FAILED only: what went wrong (no full URL: it may contain a token), and when the retry is due (0 if none). */
        public final String error;
        public final long retryAtMs;

        Snapshot(State state, int done, int total, long entries, long updatedMs, String error, long retryAtMs) {
            this.state = state;
            this.done = done;
            this.total = total;
            this.entries = entries;
            this.updatedMs = updatedMs;
            this.error = error;
            this.retryAtMs = retryAtMs;
        }
    }

    private static State state = State.NONE;
    private static int done;
    private static int total;
    private static long entries;
    private static long updatedMs;
    private static String error = "";
    private static long retryAtMs;

    private FilterStatus() {
    }

    public static synchronized Snapshot get() {
        return new Snapshot(state, done, total, entries, updatedMs, error, retryAtMs);
    }

    static synchronized void downloading(int lists) {
        state = State.DOWNLOADING;
        done = 0;
        total = lists;
        error = "";
        retryAtMs = 0;
    }

    static synchronized void listDone() {
        done++;
    }

    static synchronized void ok(long count, long modifiedMs) {
        state = State.OK;
        entries = count;
        updatedMs = modifiedMs;
        error = "";
        retryAtMs = 0;
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

    /** Reads the list that is already on disk (the ".DLD_CNT" file: count line, modification time line). */
    static synchronized void loadedFromDisk(File info) {
        if (state != State.NONE || !info.isFile()) {
            return;
        }
        try {
            String[] lines = new String(Files.readAllBytes(info.toPath()), StandardCharsets.UTF_8).split("\n");
            entries = Long.parseLong(lines[0].trim());
            updatedMs = info.lastModified();
            state = State.OK;
        } catch (Exception e) {
            // unreadable info file: the status stays NONE
        }
    }
}
