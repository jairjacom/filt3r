package com.bridge.filt3r.core.filter;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

/** What a blocked app's DNS question gets back: the same question, flagged as a refusal. */
class DNSResolverRefusedTest {

    /** A query for a.bc, type A, class IN, with id 0x1234 and recursion desired. */
    private static final byte[] QUERY = {
            0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0,
            1, 'a', 2, 'b', 'c', 0, 0, 1, 0, 1};

    @Test
    void theAnswerKeepsTheIdAndTheQuestionAndCarriesRcodeRefused() throws IOException {
        byte[] answer = DNSResolver.refused(QUERY);

        assertEquals(QUERY.length, answer.length);
        assertEquals(0x12, answer[0]);
        assertEquals(0x34, answer[1]);
        assertEquals(0x81, answer[2] & 0xFF); // QR set, RD kept
        assertEquals(5, answer[3] & 0x0F);    // RCODE 5 = REFUSED
        assertEquals(0, answer[3] & 0x70);    // reserved bits stay zero
        assertEquals(1, answer[5]);           // one question, no records
        assertArrayEquals(new byte[6], Arrays.copyOfRange(answer, 6, 12));
        assertArrayEquals(Arrays.copyOfRange(QUERY, 12, QUERY.length), Arrays.copyOfRange(answer, 12, answer.length));
    }

    @Test
    void aTruncatedQuestionIsAnErrorNotAGuess() {
        byte[] cut = Arrays.copyOf(QUERY, 16);
        assertThrows(IOException.class, () -> DNSResolver.refused(cut));
    }
}
