package com.bridge.filt3r.core.socks;

import java.net.InetAddress;

/**
 * Asked before the bridge dials: may this app's connection go on, and by which way? Runs on the connection's
 * own thread, and the app's socket is still open then, so it is the moment Android can still name the owner.
 */
public interface ConnectionGate {

    /** The gate's answer: refused (with a reason), let through the node, or let through and dialled directly. */
    final class Decision {

        /** Let through, by the node as usual. */
        public static final Decision ALLOW = new Decision(null, false);

        /**
         * Let through, dialled over the real network with no node and no IP block list: the connection of an
         * Exempt app that has a rule which does not refuse it right now (ADR 0006).
         */
        public static final Decision DIRECT = new Decision(null, true);

        private final String refusal;
        private final boolean direct;

        private Decision(String refusal, boolean direct) {
            this.refusal = refusal;
            this.direct = direct;
        }

        /** Refused for this reason (one of the REASON_ constants). */
        public static Decision refuse(String reason) {
            return new Decision(reason, false);
        }

        /** Why the connection is refused, or null when it is let through. */
        public String refusal() {
            return refusal;
        }

        /** True when a connection that is let through is dialled directly. */
        public boolean isDirect() {
            return direct;
        }
    }

    /**
     * @param client the app's own socket as hev named it; null when hev named none
     */
    Decision decide(AppSocket client, InetAddress dest, int port);
}
