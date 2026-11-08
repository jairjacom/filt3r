package com.bridge.filt3r;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CopyOnWriteArrayList;

/** Process-wide state of the tunnel, set by the service and read by the UI. */
final class TunnelState {

    enum State { STOPPED, STARTING, RUNNING, FAILED }

    interface Listener {
        void onTunnelState(State state);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile State state = State.STOPPED;
    private static volatile long pausedUntil;

    private TunnelState() {}

    static State get() {
        return state;
    }

    static void set(State next) {
        state = next;
        if (next != State.RUNNING) {
            pausedUntil = 0; // a pause belongs to one running tunnel
        }
        notifyListeners(next);
    }

    /** Wall-clock end of the filtering pause, or 0 when filtering is on. */
    static long pausedUntil() {
        long until = pausedUntil;
        return until > System.currentTimeMillis() ? until : 0;
    }

    /** Records the pause and tells the listeners, so the screen, tile and widget refresh. */
    static void setPausedUntil(long until) {
        pausedUntil = until;
        notifyListeners(state);
    }

    private static void notifyListeners(State current) {
        MAIN.post(() -> {
            for (Listener l : LISTENERS) {
                l.onTunnelState(current);
            }
        });
    }

    static void register(Listener l) {
        LISTENERS.add(l);
    }

    static void unregister(Listener l) {
        LISTENERS.remove(l);
    }
}
