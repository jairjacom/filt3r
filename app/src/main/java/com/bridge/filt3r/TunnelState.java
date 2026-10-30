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

    private TunnelState() {}

    static State get() {
        return state;
    }

    static void set(State next) {
        state = next;
        MAIN.post(() -> {
            for (Listener l : LISTENERS) {
                l.onTunnelState(next);
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
