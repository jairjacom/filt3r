package com.bridge.filt3r.core.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/** Names that only mean something on a local network are answered here and never sent to the public resolver. */
class DNSResolverLocalNamesTest {

    private static final int TYPE_A = 1;
    private static final int TYPE_PTR = 12;
    private static final int NXDOMAIN = 3;

    private final AtomicInteger upstreamCalls = new AtomicInteger();
    private final DNSResolver resolver = new DNSResolver(request -> {
        upstreamCalls.incrementAndGet();
        byte[] response = request.clone();
        response[2] = (byte) 0x81; // QR, RD
        response[3] = (byte) 0x80; // RA, NOERROR
        return response;
    });

    @Test
    void aHomeRouterNameGetsNxdomainAndStaysOffTheNetwork() throws IOException {
        byte[] answer = resolver.resolve("test", query("router.lan", TYPE_A));
        assertEquals(NXDOMAIN, answer[3] & 0x0F);
        assertEquals(0, upstreamCalls.get());
    }

    @Test
    void aPrivateReverseLookupGetsNxdomainAndStaysOffTheNetwork() throws IOException {
        byte[] answer = resolver.resolve("test", query("1.1.168.192.in-addr.arpa", TYPE_PTR));
        assertEquals(NXDOMAIN, answer[3] & 0x0F);
        assertEquals(0, upstreamCalls.get());
    }

    @Test
    void anOrdinaryNameStillGoesToTheResolver() throws IOException {
        resolver.resolve("test", query("example.com", TYPE_A));
        assertEquals(1, upstreamCalls.get());
    }

    @Test
    void localhostStillGoesToTheResolver() throws IOException {
        resolver.resolve("test", query("localhost", TYPE_A));
        assertEquals(1, upstreamCalls.get());
    }

    /** A standard recursive query for one name: 12-byte header, the labels, the type and class IN. */
    private static byte[] query(String name, int type) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x12);
        out.write(0x34); // id
        out.write(0x01);
        out.write(0x00); // flags: RD
        out.write(0);
        out.write(1); // one question
        for (int i = 0; i < 6; i++) {
            out.write(0); // no answer, authority or additional records
        }
        for (String label : name.split("\\.")) {
            byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
            out.write(bytes.length);
            out.write(bytes, 0, bytes.length);
        }
        out.write(0);
        out.write(type >> 8);
        out.write(type);
        out.write(0);
        out.write(1); // class IN
        return out.toByteArray();
    }
}
