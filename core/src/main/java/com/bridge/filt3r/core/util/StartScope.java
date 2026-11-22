package com.bridge.filt3r.core.util;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Everything one tunnel start creates, and the one rule for "is this start still current".
 * A start adopts each resource as it builds it. A stop closes the scope; a start that is still
 * running sees {@link #isClosed()} and discards what it made, and a resource adopted after the
 * close is closed at once, so nothing outlives the stop. All methods are thread safe.
 */
public final class StartScope {

    private final Deque<Runnable> closers = new ArrayDeque<>(); // guarded by this; newest first
    private boolean closed;                                     // guarded by this

    public synchronized boolean isClosed() {
        return closed;
    }

    /**
     * Registers how to release a resource.
     *
     * @return true if the scope is open and now owns it; false if it was already closed, in which
     *         case {@code closer} has been run and the caller should stop building
     */
    public boolean adopt(Runnable closer) {
        synchronized (this) {
            if (!closed) {
                closers.push(closer);
                return true;
            }
        }
        runQuietly(closer);
        return false;
    }

    /** Releases everything, newest first. Safe to call twice; one failing closer does not stop the rest. */
    public void close() {
        Deque<Runnable> todo;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            todo = new ArrayDeque<>(closers);
            closers.clear();
        }
        for (Runnable r : todo) {
            runQuietly(r);
        }
    }

    // A stop must finish: a closer that throws must not leave the other resources open.
    private static void runQuietly(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            // ignored on purpose, see above
        }
    }
}
