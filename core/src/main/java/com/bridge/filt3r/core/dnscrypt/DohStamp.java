package com.bridge.filt3r.core.dnscrypt;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import com.bridge.filt3r.core.dns.NameResolver;
import com.bridge.filt3r.core.filter.DNSResolver;
import com.bridge.filt3r.core.socks.Dialer;
import com.bridge.filt3r.core.util.IpLiteral;

/**
 * A DNS-over-HTTPS resolver from an {@code sdns://} stamp (protocol 2): host name, port, path and
 * optional IPv4 addresses (the stamp's address and its bootstrap list). The certificate hashes in the
 * stamp are not used; the normal certificate and host name check applies.
 */
public final class DohStamp implements ResolverStamp {

    static final int PROTOCOL = 0x02;

    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?");
    private static final Pattern PATH = Pattern.compile("/[\\x21-\\x7E]{0,200}");

    public final String hostname;
    public final int port;
    public final String path;
    /** IPv4 addresses from the stamp; empty if it has none (the host name is then looked up). */
    public final List<InetAddress> addresses;
    private final boolean onlyIpv6;
    /** The operator's claims, bit flags: 1 = DNSSEC, 2 = no logs, 4 = no filtering. Not verifiable. */
    public final long props;

    private DohStamp(String hostname, int port, String path, List<InetAddress> addresses, boolean onlyIpv6, long props) {
        this.hostname = hostname;
        this.port = port;
        this.path = path;
        this.addresses = addresses;
        this.onlyIpv6 = onlyIpv6;
        this.props = props;
    }

    /** The URL of the DoH endpoint. */
    public String url() {
        return "https://" + hostname + (port == 443 ? "" : ":" + port) + path;
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
        return addresses.isEmpty() ? hostname : addresses.get(0).getHostAddress();
    }

    @Override
    public boolean usable() {
        return !onlyIpv6;
    }

    @Override
    public DNSResolver.Upstream upstream(Dialer dialer, NameResolver names) {
        return new DohUpstream(this, dialer, names);
    }

    /** @throws IllegalArgumentException if this is not a well-formed DoH stamp */
    public static DohStamp parse(String stamp) {
        byte[] b = StampText.decode(stamp);
        if (b.length < 9 || b[0] != PROTOCOL) {
            throw new IllegalArgumentException("not a DoH stamp");
        }
        int[] pos = {9}; // protocol byte + 8 property bytes
        String addr = ascii(StampText.lengthPrefixed(b, pos));
        StampText.variableLength(b, pos); // certificate hashes: not used
        String hostPort = ascii(StampText.lengthPrefixed(b, pos));
        String path = ascii(StampText.lengthPrefixed(b, pos));
        List<byte[]> bootstrap = pos[0] < b.length ? StampText.variableLength(b, pos) : Collections.emptyList();

        String host = hostPort;
        int port = 0;
        int colon = hostPort.lastIndexOf(':');
        if (colon >= 0) {
            host = hostPort.substring(0, colon);
            port = StampText.port(hostPort.substring(colon));
        }
        StampText.Address a = StampText.address(addr);
        if (!HOST.matcher(host).matches()) {
            throw new IllegalArgumentException("bad host name");
        }
        if (!PATH.matcher(path).matches()) {
            throw new IllegalArgumentException("bad path");
        }
        List<InetAddress> found = new ArrayList<>();
        boolean sawIpv6 = false;
        if (a.ip != null) {
            if (a.ip instanceof Inet4Address) {
                found.add(a.ip);
            } else {
                sawIpv6 = true;
            }
            if (port == 0) {
                port = a.port;
            }
        }
        for (byte[] item : bootstrap) {
            InetAddress ip = IpLiteral.parse(ascii(item));
            if (ip == null) {
                throw new IllegalArgumentException("bad bootstrap address");
            }
            if (!(ip instanceof Inet4Address)) {
                sawIpv6 = true;
            } else if (!found.contains(ip)) {
                found.add(ip);
            }
        }
        return new DohStamp(host, port == 0 ? 443 : port, path, Collections.unmodifiableList(found),
                found.isEmpty() && sawIpv6, StampText.props(b));
    }

    private static String ascii(byte[] b) {
        return new String(b, StandardCharsets.US_ASCII);
    }
}
