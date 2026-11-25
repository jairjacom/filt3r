package com.bridge.filt3r.core.dnscrypt;

import java.io.IOException;
import java.net.InetAddress;
import java.util.List;

import com.bridge.filt3r.core.dns.NameResolver;
import com.bridge.filt3r.core.filter.DNSResolver;
import com.bridge.filt3r.core.http.HttpsFetcher;
import com.bridge.filt3r.core.socks.Dialer;

/**
 * Sends DNS queries to a DNS-over-HTTPS resolver (RFC 8484, HTTP/1.1 POST) through a {@link Dialer}.
 * Each query uses its own connection, as DNSCrypt does here. A stamp without an IP address has its
 * host name looked up through {@code names} (the built-in DNSCrypt resolver through the node); the
 * answer is kept for a few minutes and dropped after a failed query.
 */
public final class DohUpstream implements DNSResolver.Upstream {

    private static final int MAX_RESPONSE = 65_535;
    private static final int READ_TIMEOUT_MS = 6_000;
    private static final long LOOKUP_KEEP_MS = 5 * 60_000L;
    private static final String TYPE = "application/dns-message";

    private final DohStamp stamp;
    private final NameResolver names;
    private final HttpsFetcher fetcher;
    private List<InetAddress> looked; // guarded by this
    private long lookedAt; // guarded by this

    public DohUpstream(DohStamp stamp, Dialer dialer, NameResolver names) {
        this.stamp = stamp;
        this.names = names;
        this.fetcher = new HttpsFetcher(dialer, host -> addresses());
    }

    @Override
    public byte[] query(byte[] request) throws IOException {
        try {
            return fetcher.post(stamp.url(), TYPE, request, MAX_RESPONSE, READ_TIMEOUT_MS);
        } catch (IOException e) {
            forgetLookup();
            throw e;
        }
    }

    private List<InetAddress> addresses() throws IOException {
        if (!stamp.addresses.isEmpty()) {
            return stamp.addresses;
        }
        synchronized (this) {
            if (looked != null && System.currentTimeMillis() - lookedAt < LOOKUP_KEEP_MS) {
                return looked;
            }
        }
        List<InetAddress> found = names.resolve(stamp.hostname);
        synchronized (this) {
            looked = found;
            lookedAt = System.currentTimeMillis();
        }
        return found;
    }

    private synchronized void forgetLookup() {
        looked = null;
    }
}
