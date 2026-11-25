package com.bridge.filt3r.core.dnscrypt;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

import com.bridge.filt3r.core.dns.NameResolver;
import com.bridge.filt3r.core.filter.DNSResolver;
import com.bridge.filt3r.core.socks.Dialer;

/**
 * A DNSCrypt resolver from an {@code sdns://} stamp: address, provider public key (Ed25519) and
 * provider name. The address must be an IP literal, so no name lookup is ever needed.
 */
public final class DnsCryptStamp implements ResolverStamp {

    static final int PROTOCOL = 0x01;

    public final InetAddress address;
    public final int port;
    public final byte[] providerKey;
    public final String providerName;
    /** The operator's claims, bit flags: 1 = DNSSEC, 2 = no logs, 4 = no filtering. Not verifiable. */
    public final long props;

    private DnsCryptStamp(InetAddress address, int port, byte[] providerKey, String providerName, long props) {
        this.address = address;
        this.port = port;
        this.providerKey = providerKey;
        this.providerName = providerName;
        this.props = props;
    }

    @Override
    public boolean claimsDnssec() {
        return (props & 1) != 0;
    }

    @Override
    public boolean claimsNoLogs() {
        return (props & 2) != 0;
    }

    @Override
    public boolean claimsNoFilter() {
        return (props & 4) != 0;
    }

    @Override
    public String endpoint() {
        return address.getHostAddress();
    }

    @Override
    public boolean usable() {
        return !(address instanceof Inet6Address); // the tunnel path is IPv4
    }

    @Override
    public DNSResolver.Upstream upstream(Dialer dialer, NameResolver names) {
        return new DnsCryptUpstream(this, dialer);
    }

    /** @throws IllegalArgumentException if this is not a well-formed DNSCrypt stamp with an IP address */
    public static DnsCryptStamp parse(String stamp) {
        byte[] b = StampText.decode(stamp);
        if (b.length < 9 || b[0] != PROTOCOL) {
            throw new IllegalArgumentException("not a DNSCrypt stamp");
        }
        int[] pos = {9}; // protocol byte + 8 property bytes
        String addr = new String(StampText.lengthPrefixed(b, pos), StandardCharsets.US_ASCII);
        byte[] key = StampText.lengthPrefixed(b, pos);
        String provider = new String(StampText.lengthPrefixed(b, pos), StandardCharsets.US_ASCII);
        if (key.length != 32 || provider.isEmpty()) {
            throw new IllegalArgumentException("bad provider key or name");
        }
        StampText.Address a = StampText.address(addr);
        if (a.ip == null) {
            throw new IllegalArgumentException("stamp address must be an IP, not a name");
        }
        return new DnsCryptStamp(a.ip, a.port == 0 ? 443 : a.port, key, provider, StampText.props(b));
    }
}
