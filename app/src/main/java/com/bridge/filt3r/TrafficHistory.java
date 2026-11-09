package com.bridge.filt3r;

/**
 * The last few minutes of tunnel speed, one sample every {@link #INTERVAL_MS}, kept in memory only.
 * The service fills it while the tunnel is up; the Status screen reads it, so the graph is already
 * full when the app is opened. It is emptied when the tunnel stops.
 */
final class TrafficHistory {

    static final int CAPACITY = 300; // about ten minutes
    static final long INTERVAL_MS = 2000;

    /** A copy of the samples, oldest first, in bytes per second. */
    static final class Snap {
        final float[] down;
        final float[] up;
        final long peakBps;
        final long startMs;

        Snap(float[] down, float[] up, long peakBps, long startMs) {
            this.down = down;
            this.up = up;
            this.peakBps = peakBps;
            this.startMs = startMs;
        }
    }

    private static final Object LOCK = new Object();
    private static final float[] DOWN = new float[CAPACITY];
    private static final float[] UP = new float[CAPACITY];
    private static int size;
    private static int next;
    private static long lastUp = -1;
    private static long lastDown = -1;
    private static long lastMs;
    private static long peak;
    private static long startMs;

    private TrafficHistory() {
    }

    /** Forgets everything; the next sample starts a new session at {@code nowMs}. */
    static void clear(long nowMs) {
        synchronized (LOCK) {
            size = 0;
            next = 0;
            lastUp = -1;
            lastDown = -1;
            peak = 0;
            startMs = nowMs;
        }
    }

    /** Forgets the peak and the session start but keeps the graph. */
    static void resetPeak(long nowMs) {
        synchronized (LOCK) {
            peak = 0;
            startMs = nowMs;
        }
    }

    /** Adds a sample from the running byte counters (totals since the tunnel started). */
    static void sample(long upBytes, long downBytes, long nowMs) {
        synchronized (LOCK) {
            if (lastUp >= 0 && nowMs > lastMs) {
                double secs = (nowMs - lastMs) / 1000.0;
                float u = (float) Math.max(0, (upBytes - lastUp) / secs);
                float d = (float) Math.max(0, (downBytes - lastDown) / secs);
                UP[next] = u;
                DOWN[next] = d;
                next = (next + 1) % CAPACITY;
                if (size < CAPACITY) {
                    size++;
                }
                peak = Math.max(peak, (long) (u + d));
            }
            lastUp = upBytes;
            lastDown = downBytes;
            lastMs = nowMs;
        }
    }

    static Snap snapshot() {
        synchronized (LOCK) {
            float[] down = new float[size];
            float[] up = new float[size];
            int start = size < CAPACITY ? 0 : next;
            for (int i = 0; i < size; i++) {
                down[i] = DOWN[(start + i) % CAPACITY];
                up[i] = UP[(start + i) % CAPACITY];
            }
            return new Snap(down, up, peak, startMs);
        }
    }
}
