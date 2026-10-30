package com.bridge.filt3r.core.dnscrypt;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * A DNSCrypt resolver from an {@code sdns://} stamp: address, provider public key (Ed25519) and
 * provider name. The address must be an IP literal, so no name lookup is ever needed.
 */
public final class DnsCryptStamp {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final int PROTOCOL_DNSCRYPT = 0x01;

    public final InetAddress address;
    public final int port;
    public final byte[] providerKey;
    public final String providerName;

    private DnsCryptStamp(InetAddress address, int port, byte[] providerKey, String providerName) {
        this.address = address;
        this.port = port;
        this.providerKey = providerKey;
        this.providerName = providerName;
    }

    /** @throws IllegalArgumentException if this is not a well-formed DNSCrypt stamp with an IP address */
    public static DnsCryptStamp parse(String stamp) {
        String s = stamp.trim();
        if (!s.startsWith("sdns://")) {
            throw new IllegalArgumentException("not an sdns:// stamp");
        }
        byte[] b;
        try {
            b = Base64.getUrlDecoder().decode(s.substring(7));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bad stamp encoding");
        }
        if (b.length < 9 || b[0] != PROTOCOL_DNSCRYPT) {
            throw new IllegalArgumentException("not a DNSCrypt stamp");
        }
        int[] pos = {9}; // protocol byte + 8 property bytes
        String addr = new String(lengthPrefixed(b, pos), StandardCharsets.US_ASCII);
        byte[] key = lengthPrefixed(b, pos);
        String provider = new String(lengthPrefixed(b, pos), StandardCharsets.US_ASCII);
        if (key.length != 32 || provider.isEmpty()) {
            throw new IllegalArgumentException("bad provider key or name");
        }
        return hostPort(addr, key, provider);
    }

    private static DnsCryptStamp hostPort(String addr, byte[] key, String provider) {
        String host = addr;
        int port = 443;
        if (addr.startsWith("[")) {
            int end = addr.indexOf(']');
            if (end < 0) {
                throw new IllegalArgumentException("bad address");
            }
            host = addr.substring(1, end);
            if (end + 1 < addr.length()) {
                port = port(addr.substring(end + 1));
            }
        } else if (addr.indexOf(':') == addr.lastIndexOf(':') && addr.indexOf(':') >= 0) {
            host = addr.substring(0, addr.indexOf(':'));
            port = port(addr.substring(addr.indexOf(':')));
        }
        boolean v6 = host.indexOf(':') >= 0;
        if (!v6 && !IPV4.matcher(host).matches()) {
            throw new IllegalArgumentException("stamp address must be an IP, not a name");
        }
        try {
            return new DnsCryptStamp(InetAddress.getByName(host), port, key, provider); // literal: no lookup
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("bad address");
        }
    }

    /** @param colonPort for example ":443" */
    private static int port(String colonPort) {
        try {
            int p = Integer.parseInt(colonPort.substring(1));
            if (p < 1 || p > 65535) {
                throw new NumberFormatException();
            }
            return p;
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            throw new IllegalArgumentException("bad port");
        }
    }

    private static byte[] lengthPrefixed(byte[] b, int[] pos) {
        if (pos[0] >= b.length) {
            throw new IllegalArgumentException("truncated stamp");
        }
        int n = b[pos[0]] & 0xFF;
        if (pos[0] + 1 + n > b.length) {
            throw new IllegalArgumentException("truncated stamp");
        }
        byte[] out = java.util.Arrays.copyOfRange(b, pos[0] + 1, pos[0] + 1 + n);
        pos[0] += 1 + n;
        return out;
    }
}
