package com.bridge.filt3r.core.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** CIDR ranges next to single addresses in an IP list. */
class IpBlockListTest {

    private static IpBlockList list(String text) throws IOException {
        return IpBlockList.parse(new ByteArrayInputStream(text.getBytes(StandardCharsets.US_ASCII)));
    }

    private static boolean blocked(IpBlockList l, String ip) throws IOException {
        return l.isBlocked(InetAddress.getByAddress(ipBytes(ip)));
    }

    private static byte[] ipBytes(String ip) throws IOException {
        return com.bridge.filt3r.core.util.IpLiteral.parse(ip).getAddress();
    }

    @Test
    void aRangeBlocksEveryAddressInsideAndNothingOutside() throws IOException {
        IpBlockList l = list("203.0.113.0/24\n");
        assertTrue(blocked(l, "203.0.113.0"));
        assertTrue(blocked(l, "203.0.113.255"));
        assertFalse(blocked(l, "203.0.112.255"));
        assertFalse(blocked(l, "203.0.114.0"));
    }

    @Test
    void rangesAboveAndBelow128DotZeroBothWork() throws IOException {
        // the signed-int order trick must hold on both sides of 128.0.0.0
        IpBlockList l = list("100.64.0.0/10\n198.51.100.0/22\n");
        assertTrue(blocked(l, "100.127.255.255"));
        assertFalse(blocked(l, "100.128.0.0"));
        assertTrue(blocked(l, "198.51.103.1"));
        assertFalse(blocked(l, "198.51.104.0"));
    }

    @Test
    void overlappingAndNestedRangesAreMerged() throws IOException {
        IpBlockList l = list("10.0.0.0/16\n10.0.5.0/24\n10.0.128.0/17\n10.1.0.0/16\n");
        assertTrue(blocked(l, "10.0.200.1"));
        assertTrue(blocked(l, "10.1.9.9"));
        assertFalse(blocked(l, "10.2.0.0"));
        assertEquals(2, l.size()); // the first three lie inside 10.0.0.0/16 and merge into it; 10.1.0.0/16 stays apart
    }

    @Test
    void singleAddressesStillWorkAlongsideRanges() throws IOException {
        IpBlockList l = list("# mine\n192.0.2.7\n203.0.113.0/24 trailing words\n192.0.2.9/32\n");
        assertTrue(blocked(l, "192.0.2.7"));
        assertTrue(blocked(l, "192.0.2.9"));
        assertFalse(blocked(l, "192.0.2.8"));
        assertTrue(blocked(l, "203.0.113.77"));
    }

    @Test
    void ipv6PrefixesMatchTheirBitsOnly() throws IOException {
        IpBlockList l = list("2001:db8::/33\n2001:db9::1\n");
        assertTrue(blocked(l, "2001:db8:7fff::1"));
        assertFalse(blocked(l, "2001:db8:8000::1"));
        assertTrue(blocked(l, "2001:db9::1"));
        assertFalse(blocked(l, "2001:db9::2"));
    }

    @Test
    void rangesThatWouldBlockTheInternetAreIgnored() throws IOException {
        IpBlockList l = list("0.0.0.0/0\n10.0.0.0/4\n::/0\n203.0.113.5\n");
        assertFalse(blocked(l, "8.8.8.8"));
        assertTrue(blocked(l, "203.0.113.5"));
        assertEquals(1, l.size());
    }

    @Test
    void badPrefixLengthsAreSkipped() throws IOException {
        IpBlockList l = list("203.0.113.0/33\n203.0.113.0/x\n203.0.113.0/\n198.51.100.1\n");
        assertFalse(blocked(l, "203.0.113.1"));
        assertTrue(blocked(l, "198.51.100.1"));
    }

    @Test
    void plusKeepsBothListsEntries() throws IOException {
        IpBlockList both = list("192.0.2.1\n").plus(list("203.0.113.0/24\n2001:db8::/32\n"));
        assertTrue(blocked(both, "192.0.2.1"));
        assertTrue(blocked(both, "203.0.113.9"));
        assertTrue(blocked(both, "2001:db8::5"));
        assertFalse(blocked(both, "192.0.2.2"));
    }

    @Test
    void aTextWithNoAddressIsStillAnError() {
        assertThrows(IOException.class, () -> list("<html>not found</html>\n"));
    }
}
