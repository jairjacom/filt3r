package com.bridge.filt3r.core.socks;

import java.io.IOException;

/** Answers one DNS query that reached the bridge. Both sides are plain DNS messages. */
public interface DnsHandler {

    /**
     * @param client the app's own socket inside the tunnel, or null when hev did not name it
     * @param query  one DNS query message
     * @return the response message, or null to send no answer
     */
    byte[] handle(AppSocket client, byte[] query) throws IOException;
}
