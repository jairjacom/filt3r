package com.bridge.filt3r.core.util;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Strict IP literal parsing that never touches DNS. {@code InetAddress.getByName} falls back to a
 * lookup through the system resolver for text that is not a valid literal (for example
 * "999.1.1.1"), which would send a node or resolver address outside the tunnel.
 */
public final class IpLiteral {

    private IpLiteral() {
    }

    /** @return the address, or null if the text is not a valid IPv4 or IPv6 literal (no zone ids, no brackets) */
    public static InetAddress parse(String s) {
        if (s == null) {
            return null;
        }
        byte[] b = s.indexOf(':') >= 0 ? v6(s) : v4(s);
        if (b == null) {
            return null;
        }
        try {
            return InetAddress.getByAddress(b); // raw bytes: no lookup
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static byte[] v4(String s) {
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.length() > 3) {
                return null;
            }
            int v = 0;
            for (int k = 0; k < p.length(); k++) {
                char c = p.charAt(k);
                if (c < '0' || c > '9') {
                    return null;
                }
                v = v * 10 + (c - '0');
            }
            if (v > 255) {
                return null;
            }
            out[i] = (byte) v;
        }
        return out;
    }

    private static byte[] v6(String s) {
        int dbl = s.indexOf("::");
        if (dbl >= 0 && s.indexOf("::", dbl + 1) >= 0) {
            return null; // more than one "::"
        }
        String head = dbl < 0 ? s : s.substring(0, dbl);
        String tail = dbl < 0 ? "" : s.substring(dbl + 2);
        int[] groups = new int[8];
        int headCount = fill(head, groups, 0, dbl < 0);
        if (headCount < 0) {
            return null;
        }
        if (dbl < 0) {
            if (headCount != 8) {
                return null;
            }
        } else {
            int[] t = new int[8];
            int tailCount = fill(tail, t, 0, true);
            if (tailCount < 0 || headCount + tailCount > 7) {
                return null; // "::" stands for at least one group
            }
            System.arraycopy(t, 0, groups, 8 - tailCount, tailCount);
        }
        byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) {
            out[2 * i] = (byte) (groups[i] >> 8);
            out[2 * i + 1] = (byte) groups[i];
        }
        return out;
    }

    /**
     * Parses colon separated groups into {@code into}. An IPv4 tail is allowed only if {@code last}.
     * @return the number of 16-bit groups, or -1 if malformed
     */
    private static int fill(String text, int[] into, int from, boolean last) {
        if (text.isEmpty()) {
            return 0;
        }
        String[] parts = text.split(":", -1);
        int n = from;
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.indexOf('.') >= 0) {
                byte[] v = i == parts.length - 1 && last ? v4(p) : null;
                if (v == null || n + 2 > 8) {
                    return -1;
                }
                into[n++] = ((v[0] & 0xFF) << 8) | (v[1] & 0xFF);
                into[n++] = ((v[2] & 0xFF) << 8) | (v[3] & 0xFF);
                continue;
            }
            if (p.isEmpty() || p.length() > 4 || n >= 8) {
                return -1;
            }
            int v = 0;
            for (int k = 0; k < p.length(); k++) {
                char c = p.charAt(k);
                int d = c < 128 ? Character.digit(c, 16) : -1;
                if (d < 0) {
                    return -1;
                }
                v = v * 16 + d;
            }
            into[n++] = v;
        }
        return n - from;
    }
}
