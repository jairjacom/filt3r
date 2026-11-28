package com.bridge.filt3r.core.firewall;

/** The firewall's answer for one flow: refused (with a reason), let through the node, or let through and dialled directly. */
public final class Verdict {

    /** Let through, by the node as usual. */
    public static final Verdict ALLOW = new Verdict(null, false);

    /**
     * Let through, dialled over the real network with no node and no IP block list: the flow of an Exempt app that
     * has a rule which does not refuse it right now (ADR 0006).
     */
    public static final Verdict DIRECT = new Verdict(null, true);

    private final String refusal;
    private final boolean direct;

    private Verdict(String refusal, boolean direct) {
        this.refusal = refusal;
        this.direct = direct;
    }

    /** Refused for this reason (one of the FirewallPolicy REASON_ constants). */
    public static Verdict refuse(String reason) {
        return new Verdict(reason, false);
    }

    /** Why the flow is refused, or null when it is let through. */
    public String refusal() {
        return refusal;
    }

    /** True when a flow that is let through is dialled directly. */
    public boolean isDirect() {
        return direct;
    }
}
