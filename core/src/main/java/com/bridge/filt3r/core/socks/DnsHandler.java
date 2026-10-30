package com.bridge.filt3r.core.socks;

import java.io.IOException;

/** Answers one DNS query that reached the bridge. Both sides are plain DNS messages. */
public interface DnsHandler {

    /**
     * @param client label for the query log
     * @param query  one DNS query message
     * @return the response message, or null to send no answer
     */
    byte[] handle(String client, byte[] query) throws IOException;
}
