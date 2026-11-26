package com.bridge.filt3r.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** What a DNS response says about the name that was asked and the addresses it gave back. */
class DnsAnswersTest {

    private static final byte[] A_RECORD = {0, 1};
    private static final byte[] AAAA_RECORD = {0, 28};
    private static final byte[] CNAME_RECORD = {0, 5};

    @Test
    void anAAnswerGivesTheAskedNameAndTheAddress() {
        byte[] message = message("Example.ORG", 1,
                answer(A_RECORD, new byte[] {93, (byte) 184, (byte) 216, 34}));

        DnsAnswers.Answer a = DnsAnswers.parse(message);

        assertEquals("example.org", a.name);
        assertEquals(1, a.addresses.size());
        assertEquals("93.184.216.34", a.addresses.get(0).getHostAddress());
    }

    @Test
    void anAaaaAnswerGivesTheIpv6Address() {
        byte[] v6 = new byte[16];
        v6[0] = 0x20;
        v6[1] = 0x01;
        v6[2] = 0x0d;
        v6[3] = (byte) 0xb8;
        v6[15] = 1;
        DnsAnswers.Answer a = DnsAnswers.parse(message("example.org", 1, answer(AAAA_RECORD, v6)));

        assertEquals(1, a.addresses.size());
        assertEquals("2001:db8:0:0:0:0:0:1", a.addresses.get(0).getHostAddress());
    }

    @Test
    void aCnameInTheChainIsSkippedAndTheAddressesAfterItAreKept() {
        byte[] cname = new byte[] {3, 'w', 'w', 'w', (byte) 0xC0, 12}; // www + pointer to the question name
        byte[] message = message("example.org", 2,
                answer(CNAME_RECORD, cname),
                answer(A_RECORD, new byte[] {10, 1, 2, 3}));

        DnsAnswers.Answer a = DnsAnswers.parse(message);

        assertEquals(1, a.addresses.size());
        assertEquals("10.1.2.3", a.addresses.get(0).getHostAddress());
    }

    @Test
    void aResponseWithNoAnswersStillNamesTheQuestion() {
        DnsAnswers.Answer a = DnsAnswers.parse(message("blocked.example", 0));

        assertEquals("blocked.example", a.name);
        assertTrue(a.addresses.isEmpty());
    }

    @Test
    void aTruncatedOrEmptyMessageGivesNull() {
        byte[] whole = message("example.org", 1, answer(A_RECORD, new byte[] {1, 2, 3, 4}));

        assertNull(DnsAnswers.parse(null));
        assertNull(DnsAnswers.parse(new byte[5]));
        assertNull(DnsAnswers.parse(Arrays.copyOf(whole, whole.length - 2)));
    }

    /** Header, one question, then the answers; each answer's name is a pointer to the question name. */
    private static byte[] message(String name, int answerCount, byte[]... answers) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x12);
        out.write(0x34);
        out.write(0x81);
        out.write(0x80);
        out.write(0);
        out.write(1); // one question
        out.write(0);
        out.write(answerCount);
        out.write(new byte[4], 0, 4);
        for (String label : name.split("\\.")) {
            out.write(label.length());
            out.write(label.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, label.length());
        }
        out.write(0);
        out.write(new byte[] {0, 1, 0, 1}, 0, 4); // type A, class IN
        for (byte[] answer : answers) {
            out.write(answer, 0, answer.length);
        }
        return out.toByteArray();
    }

    private static byte[] answer(byte[] type, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0xC0);
        out.write(12); // the name: a pointer to the question
        out.write(type, 0, 2);
        out.write(new byte[] {0, 1, 0, 0, 0, 60}, 0, 6); // class IN, ttl 60
        out.write(0);
        out.write(data.length);
        out.write(data, 0, data.length);
        return out.toByteArray();
    }
}
