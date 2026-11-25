package com.bridge.filt3r.core.dnscrypt;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import com.bridge.filt3r.core.util.IpLiteral;

/** The byte layout shared by all {@code sdns://} stamps (https://dnscrypt.info/stamps-specifications). */
final class StampText {

    /** An address field: an IP literal (null when the field is empty) and an optional port (0 when absent). */
    static final class Address {
        final InetAddress ip;
        final int port;

        Address(InetAddress ip, int port) {
            this.ip = ip;
            this.port = port;
        }
    }

    private StampText() {
    }

    /** @return the bytes after "sdns://"; the first one is the protocol */
    static byte[] decode(String stamp) {
        String s = stamp.trim();
        if (!s.startsWith("sdns://")) {
            throw new IllegalArgumentException("not an sdns:// stamp");
        }
        try {
            return Base64.getUrlDecoder().decode(s.substring(7));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bad stamp encoding");
        }
    }

    /** The 8 property bytes after the protocol byte, little endian. */
    static long props(byte[] b) {
        long props = 0;
        for (int i = 8; i >= 1; i--) {
            props = props << 8 | (b[i] & 0xFF);
        }
        return props;
    }

    /** One item that starts with its length byte. Moves {@code pos} past it. */
    static byte[] lengthPrefixed(byte[] b, int[] pos) {
        if (pos[0] >= b.length) {
            throw new IllegalArgumentException("truncated stamp");
        }
        int n = b[pos[0]] & 0xFF;
        if (pos[0] + 1 + n > b.length) {
            throw new IllegalArgumentException("truncated stamp");
        }
        byte[] out = Arrays.copyOfRange(b, pos[0] + 1, pos[0] + 1 + n);
        pos[0] += 1 + n;
        return out;
    }

    /** A set of items: each length byte with its top bit set says another item follows. */
    static List<byte[]> variableLength(byte[] b, int[] pos) {
        List<byte[]> out = new ArrayList<>();
        while (true) {
            if (pos[0] >= b.length) {
                throw new IllegalArgumentException("truncated stamp");
            }
            boolean more = (b[pos[0]] & 0x80) != 0;
            int n = b[pos[0]] & 0x7F;
            if (pos[0] + 1 + n > b.length) {
                throw new IllegalArgumentException("truncated stamp");
            }
            out.add(Arrays.copyOfRange(b, pos[0] + 1, pos[0] + 1 + n));
            pos[0] += 1 + n;
            if (!more) {
                return out;
            }
        }
    }

    /** Reads "", ":port", "ip", "ip:port", "[v6]" or "[v6]:port" without ever looking a name up; an empty field gives a null IP. */
    static Address address(String addr) {
        if (addr.isEmpty()) {
            return new Address(null, 0);
        }
        if (addr.startsWith(":")) { // just a port: the host name is looked up
            return new Address(null, port(addr));
        }
        String host = addr;
        int port = 0;
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
        InetAddress ip = IpLiteral.parse(host); // never a lookup, even for text that is almost an address
        if (ip == null) {
            throw new IllegalArgumentException("stamp address must be an IP, not a name");
        }
        return new Address(ip, port);
    }

    /** @param colonPort for example ":443" */
    static int port(String colonPort) {
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
}
