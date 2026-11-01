package com.bridge.filt3r.core.dns;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Just enough DNS wire format to ask one question and read the answer records. */
public final class DnsWire {

    public static final int TYPE_A = 1;
    public static final int TYPE_TXT = 16;
    public static final int TYPE_AAAA = 28;

    private static final SecureRandom RANDOM = new SecureRandom();

    private DnsWire() {
    }

    /** A recursive query for one name, class IN, with a random id and no extensions. */
    public static byte[] query(String name, int type) {
        ByteArrayOutputStream q = new ByteArrayOutputStream();
        byte[] id = new byte[2];
        RANDOM.nextBytes(id);
        q.write(id, 0, 2);
        q.write(0x01); // flags: recursion desired
        q.write(0x00);
        q.write(0); // one question, nothing else
        q.write(1);
        for (int i = 0; i < 6; i++) {
            q.write(0);
        }
        for (String label : name.split("\\.")) {
            byte[] l = label.getBytes(StandardCharsets.US_ASCII);
            if (l.length == 0 || l.length > 63) {
                throw new IllegalArgumentException("bad DNS name");
            }
            q.write(l.length);
            q.write(l, 0, l.length);
        }
        q.write(0);
        q.write(type >> 8);
        q.write(type);
        q.write(0);
        q.write(1); // IN
        return q.toByteArray();
    }

    /** @return the RDATA of every answer record of this type; @throws IOException on a failure code or bad message */
    public static List<byte[]> answers(byte[] m, int type) throws IOException {
        List<byte[]> out = new ArrayList<>();
        try {
            if (m.length < 12) {
                throw new IOException("short DNS response");
            }
            int rcode = m[3] & 0x0F;
            if (rcode != 0) {
                throw new IOException("DNS query failed (rcode " + rcode + ")");
            }
            int questions = u16(m, 4);
            int answers = u16(m, 6);
            int pos = 12;
            for (int i = 0; i < questions; i++) {
                pos = skipName(m, pos) + 4;
            }
            for (int i = 0; i < answers; i++) {
                pos = skipName(m, pos);
                int rtype = u16(m, pos);
                int rdlen = u16(m, pos + 8);
                pos += 10;
                if (rdlen > m.length - pos) {
                    throw new IOException("malformed DNS response"); // copyOfRange would pad with zeros
                }
                if (rtype == type) {
                    out.add(Arrays.copyOfRange(m, pos, pos + rdlen));
                }
                pos += rdlen;
            }
        } catch (IndexOutOfBoundsException e) {
            throw new IOException("malformed DNS response");
        }
        return out;
    }

    /** The A and AAAA addresses in a response, in answer order. */
    public static List<InetAddress> addresses(byte[] m) throws IOException {
        List<InetAddress> out = new ArrayList<>();
        for (byte[] a : answers(m, TYPE_A)) {
            if (a.length != 4) {
                throw new IOException("malformed DNS response");
            }
            out.add(InetAddress.getByAddress(a));
        }
        for (byte[] a : answers(m, TYPE_AAAA)) {
            if (a.length != 16) {
                throw new IOException("malformed DNS response");
            }
            out.add(InetAddress.getByAddress(a));
        }
        return out;
    }

    /** The character strings inside one TXT record's RDATA. */
    public static List<byte[]> txtStrings(byte[] rdata) {
        List<byte[]> out = new ArrayList<>();
        for (int p = 0; p < rdata.length; p += 1 + (rdata[p] & 0xFF)) {
            out.add(Arrays.copyOfRange(rdata, p + 1, Math.min(rdata.length, p + 1 + (rdata[p] & 0xFF))));
        }
        return out;
    }

    private static int skipName(byte[] m, int pos) {
        while (true) {
            int len = m[pos] & 0xFF;
            if (len == 0) {
                return pos + 1;
            }
            if ((len & 0xC0) == 0xC0) {
                return pos + 2;
            }
            pos += 1 + len;
        }
    }

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) << 8 | (b[off + 1] & 0xFF);
    }
}
