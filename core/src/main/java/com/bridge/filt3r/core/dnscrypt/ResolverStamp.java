package com.bridge.filt3r.core.dnscrypt;

import com.bridge.filt3r.core.dns.NameResolver;
import com.bridge.filt3r.core.filter.DNSResolver;
import com.bridge.filt3r.core.socks.Dialer;

/** A resolver from an {@code sdns://} stamp: DNSCrypt (protocol 1) or DNS-over-HTTPS (protocol 2). */
public interface ResolverStamp {

    /** The operator's claim that it validates DNSSEC. Not verifiable. */
    boolean claimsDnssec();

    /** The operator's claim that it keeps no logs. Not verifiable. */
    boolean claimsNoLogs();

    /** The operator's claim that it does not filter. Not verifiable. */
    boolean claimsNoFilter();

    /** The IP address, or the host name when the stamp has none, for display. */
    String endpoint();

    /** False if the tunnel cannot reach it (an IPv6-only address). */
    boolean usable();

    /**
     * Opens the way to this resolver through {@code dialer}.
     *
     * @param names looks up the host name of a DoH stamp that carries no IP address; not used for DNSCrypt
     */
    DNSResolver.Upstream upstream(Dialer dialer, NameResolver names);

    /** @throws IllegalArgumentException if this is not a well-formed DNSCrypt or DoH stamp we can use */
    static ResolverStamp parse(String stamp) {
        byte[] b = StampText.decode(stamp);
        if (b.length > 0 && b[0] == DohStamp.PROTOCOL) {
            return DohStamp.parse(stamp);
        }
        return DnsCryptStamp.parse(stamp);
    }
}
