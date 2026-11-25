package com.bridge.filt3r.core.dnscrypt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

class DohStampTest {

    private static final String GOOGLE = build(2, 5, "", new byte[0][], "dns.google", "/dns-query", new String[0]);
    private static final String CLOUDFLARE = "sdns://AgcAAAAAAAAABzEuMC4wLjEAEmNsb3VkZmxhcmUtZG5zLmNvbQovZG5zLXF1ZXJ5";

    @Test
    void stampWithoutAddressKeepsTheHostNameForALookup() {
        DohStamp s = DohStamp.parse(GOOGLE);
        assertEquals("dns.google", s.hostname);
        assertEquals(443, s.port);
        assertEquals("/dns-query", s.path);
        assertTrue(s.addresses.isEmpty());
        assertTrue(s.usable());
        assertEquals("dns.google", s.endpoint());
        assertEquals("https://dns.google/dns-query", s.url());
        assertTrue(s.claimsDnssec());
        assertTrue(s.claimsNoFilter());
        assertFalse(s.claimsNoLogs());
    }

    @Test
    void stampWithAddressUsesIt() {
        DohStamp s = DohStamp.parse(CLOUDFLARE);
        assertEquals("cloudflare-dns.com", s.hostname);
        assertEquals("1.0.0.1", s.endpoint());
        assertEquals(1, s.addresses.size());
    }

    @Test
    void portComesFromTheHostNameField() {
        DohStamp s = DohStamp.parse(build(2, 0, "9.9.9.9", new byte[0][], "dns.example.net:8443", "/q", new String[0]));
        assertEquals(8443, s.port);
        assertEquals("https://dns.example.net:8443/q", s.url());
    }

    @Test
    void portFallsBackToTheAddressFieldThenTo443() {
        assertEquals(4443, DohStamp.parse(build(2, 0, "9.9.9.9:4443", new byte[0][], "dns.example.net", "/q", new String[0])).port);
        assertEquals(443, DohStamp.parse(build(2, 0, "9.9.9.9", new byte[0][], "dns.example.net", "/q", new String[0])).port);
    }

    @Test
    void certificateHashesAreSkippedWhetherOneOrMany() {
        byte[][] hashes = {new byte[32], new byte[32]};
        DohStamp s = DohStamp.parse(build(2, 0, "9.9.9.10", hashes, "dns.example.net", "/q", new String[0]));
        assertEquals("dns.example.net", s.hostname);
        assertEquals("9.9.9.10", s.endpoint());
    }

    @Test
    void bootstrapListNamesPlainDnsServersSoItIsIgnored() {
        DohStamp s = DohStamp.parse(build(2, 0, "", new byte[0][], "dns.example.net", "/q", new String[] {"9.9.9.11", "2001:db8::1"}));
        assertTrue(s.addresses.isEmpty());
        assertTrue(s.usable());
        assertEquals("dns.example.net", s.endpoint());
    }

    @Test
    void addressWithJustAPortMeansLookUpTheNameAndUsesThatPort() {
        DohStamp s = DohStamp.parse(build(2, 0, ":8443", new byte[0][], "dns.example.net", "/q", new String[0]));
        assertTrue(s.addresses.isEmpty());
        assertEquals(8443, s.port);
    }

    @Test
    void anIpv6OnlyStampIsNotUsable() {
        DohStamp v6 = DohStamp.parse(build(2, 0, "[2001:db8::1]", new byte[0][], "dns.example.net", "/q", new String[0]));
        assertTrue(v6.addresses.isEmpty());
        assertFalse(v6.usable());
    }

    @Test
    void badStampsAreRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> DohStamp.parse(build(2, 0, "", new byte[0][], "bad host", "/q", new String[0])));
        assertThrows(IllegalArgumentException.class,
                () -> DohStamp.parse(build(2, 0, "", new byte[0][], "dns.example.net", "no-slash", new String[0])));
        assertThrows(IllegalArgumentException.class,
                () -> DohStamp.parse(build(2, 0, "", new byte[0][], "dns.example.net", "/a b", new String[0])));
        assertThrows(IllegalArgumentException.class,
                () -> DohStamp.parse(build(2, 0, "dns.example.net", new byte[0][], "dns.example.net", "/q", new String[0])));
        assertThrows(IllegalArgumentException.class, () -> DohStamp.parse(GOOGLE.substring(0, GOOGLE.length() - 6)));
        assertThrows(IllegalArgumentException.class, () -> DohStamp.parse("sdns://!!!"));
    }

    @Test
    void parseRoutesByProtocolAndRefusesOtherTypes() {
        assertInstanceOf(DohStamp.class, ResolverStamp.parse(GOOGLE));
        assertInstanceOf(DnsCryptStamp.class, ResolverStamp.parse(dnsCrypt("9.9.9.9:8443")));
        assertThrows(IllegalArgumentException.class,
                () -> ResolverStamp.parse(build(0x81, 0, "9.9.9.9", new byte[0][], "relay", "/", new String[0])));
    }

    @Test
    void resolverListKeepsDohAndDnsCryptAndSkipsRelaysAndIpv6Only() {
        String md = "## doh-a\nDoH one\n" + GOOGLE + "\n"
                + "## crypt-b\nDNSCrypt two\n" + dnsCrypt("9.9.9.9") + "\n"
                + "## relay-c\nrelay\n" + build(0x81, 0, "9.9.9.9", new byte[0][], "r", "/", new String[0]) + "\n"
                + "## v6-d\nonly v6\n" + build(2, 0, "[2001:db8::1]", new byte[0][], "dns.example.net", "/q", new String[0]) + "\n";
        List<ResolverList.Entry> entries = ResolverList.parse(md);
        assertEquals(2, entries.size());
        assertEquals("doh-a", entries.get(0).name);
        assertInstanceOf(DohStamp.class, entries.get(0).stamp);
        assertEquals("crypt-b", entries.get(1).name);
    }

    private static String dnsCrypt(String addr) {
        return build(1, 0, addr, new byte[][] {new byte[32]}, "2.dnscrypt-cert.example", null, null);
    }

    /**
     * Builds a stamp. DNSCrypt layout (path null): addr, key, provider. DoH layout: addr, hashes
     * (variable length), host, path, then optional bootstrap addresses.
     */
    private static String build(int protocol, long props, String addr, byte[][] hashes, String host, String path, String[] bootstrap) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(protocol);
        for (int i = 0; i < 8; i++) {
            o.write((int) (props >> (8 * i)) & 0xFF);
        }
        lp(o, addr.getBytes(StandardCharsets.US_ASCII));
        if (path == null) {
            lp(o, hashes[0]); // the provider key
            lp(o, host.getBytes(StandardCharsets.US_ASCII)); // the provider name
        } else {
            vlp(o, hashes);
            lp(o, host.getBytes(StandardCharsets.US_ASCII));
            lp(o, path.getBytes(StandardCharsets.US_ASCII));
            if (bootstrap.length > 0) {
                byte[][] items = new byte[bootstrap.length][];
                for (int i = 0; i < bootstrap.length; i++) {
                    items[i] = bootstrap[i].getBytes(StandardCharsets.US_ASCII);
                }
                vlp(o, items);
            }
        }
        return "sdns://" + Base64.getUrlEncoder().withoutPadding().encodeToString(o.toByteArray());
    }

    private static void lp(ByteArrayOutputStream o, byte[] b) {
        o.write(b.length);
        o.write(b, 0, b.length);
    }

    /** A set: all but the last item carry the top bit in their length byte; an empty set is one empty item. */
    private static void vlp(ByteArrayOutputStream o, byte[][] items) {
        if (items.length == 0) {
            o.write(0);
            return;
        }
        for (int i = 0; i < items.length; i++) {
            o.write(items[i].length | (i < items.length - 1 ? 0x80 : 0));
            o.write(items[i], 0, items[i].length);
        }
    }
}
