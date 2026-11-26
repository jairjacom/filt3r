package com.bridge.filt3r.core.socks;

import java.io.IOException;

/** Answers one DNS query that reached the bridge. Both sides are plain DNS messages. */
public interface DnsHandler {

    /**
     * @param client label for the query log: "udp:&lt;ip&gt;#&lt;port&gt;" or "tcp:&lt;ip&gt;#&lt;port&gt;" of the app's
     *               own socket inside the tunnel, or "tunnel" when hev did not name it
     * @param query  one DNS query message
     * @return the response message, or null to send no answer
     */
    byte[] handle(String client, byte[] query) throws IOException;
}
