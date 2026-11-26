package com.bridge.filt3r.core.util;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Reads what a DNS response says about the name that was asked (its first question) and the
 * A and AAAA addresses it gave back, so a connection to one of those addresses can be logged by name.
 * It reads only; a message that does not fit gives null.
 */
public final class DnsAnswers {

    /** The asked name, lower case, and the addresses in the answer section (empty for a refusal or an empty answer). */
    public static final class Answer {
        public final String name;
        public final List<InetAddress> addresses;

        Answer(String name, List<InetAddress> addresses) {
            this.name = name;
            this.addresses = Collections.unmodifiableList(addresses);
        }
    }

    private static final int HEADER = 12;
    private static final int TYPE_A = 1;
    private static final int TYPE_AAAA = 28;

    private DnsAnswers() {
    }

    /** @return null when the message is missing, cut short or has no question */
    public static Answer parse(byte[] message) {
        if (message == null || message.length < HEADER) {
            return null;
        }
        int questions = u16(message, 4);
        int answers = u16(message, 6);
        if (questions < 1) {
            return null;
        }
        StringBuilder name = new StringBuilder();
        int pos = readQuestionName(message, HEADER, name);
        if (pos < 0 || pos + 4 > message.length) {
            return null;
        }
        pos += 4; // type and class
        for (int q = 1; q < questions; q++) {
            pos = skipName(message, pos);
            if (pos < 0 || pos + 4 > message.length) {
                return null;
            }
            pos += 4;
        }
        List<InetAddress> found = new ArrayList<>();
        for (int i = 0; i < answers; i++) {
            pos = skipName(message, pos);
            if (pos < 0 || pos + 10 > message.length) {
                return null;
            }
            int type = u16(message, pos);
            int length = u16(message, pos + 8);
            pos += 10;
            if (pos + length > message.length) {
                return null;
            }
            if (type == TYPE_A && length == 4 || type == TYPE_AAAA && length == 16) {
                try {
                    byte[] raw = new byte[length];
                    System.arraycopy(message, pos, raw, 0, length);
                    found.add(InetAddress.getByAddress(raw));
                } catch (UnknownHostException e) {
                    return null; // cannot happen for 4 or 16 bytes
                }
            }
            pos += length;
        }
        return new Answer(name.toString().toLowerCase(Locale.ROOT), found);
    }

    /** The question's name is written out in full; returns the position after it, or -1. */
    private static int readQuestionName(byte[] m, int pos, StringBuilder out) {
        while (true) {
            if (pos >= m.length) {
                return -1;
            }
            int len = m[pos] & 0xFF;
            if (len == 0) {
                return pos + 1;
            }
            if (len > 63 || pos + 1 + len > m.length) {
                return -1; // a pointer (0xC0) cannot start the question, and labels are at most 63 bytes
            }
            if (out.length() > 0) {
                out.append('.');
            }
            for (int i = 0; i < len; i++) {
                out.append((char) (m[pos + 1 + i] & 0xFF));
            }
            pos += 1 + len;
        }
    }

    /** Position after a name that may end in a compression pointer; -1 when it runs off the message. */
    private static int skipName(byte[] m, int pos) {
        while (true) {
            if (pos >= m.length) {
                return -1;
            }
            int len = m[pos] & 0xFF;
            if (len == 0) {
                return pos + 1;
            }
            if ((len & 0xC0) == 0xC0) {
                return pos + 2 <= m.length ? pos + 2 : -1;
            }
            if (len > 63) {
                return -1;
            }
            pos += 1 + len;
        }
    }

    private static int u16(byte[] m, int pos) {
        return (m[pos] & 0xFF) << 8 | (m[pos + 1] & 0xFF);
    }
}
