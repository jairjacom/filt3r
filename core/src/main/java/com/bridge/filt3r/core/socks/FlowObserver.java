package com.bridge.filt3r.core.socks;

/** Spike: receives each new flow with the app-side source that hev reported for it. */
public interface FlowObserver {
    /**
     * @param tcp     true for a TCP connect, false for a DNS (UDP) association
     * @param source  "address#port" of the app's own socket inside the tunnel
     * @param dstIp   destination address bytes, or null for a domain name
     * @param dstPort destination port
     */
    void onFlow(boolean tcp, String source, byte[] dstIp, int dstPort);
}
