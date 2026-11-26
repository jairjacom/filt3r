package com.bridge.filt3r.core.socks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** The app's own socket as hev names it after the bridge user name: "address#port". */
class AppSocketTest {

    @Test
    void anIpv4SourceKeepsItsProtocolAddressAndPort() {
        AppSocket s = AppSocket.parse(AppSocket.Proto.UDP, "10.0.2.15#40000");

        assertEquals(AppSocket.Proto.UDP, s.proto());
        assertEquals("10.0.2.15", s.ip().getHostAddress());
        assertEquals(40000, s.port());
        assertEquals("udp:10.0.2.15#40000", s.toString());
    }

    @Test
    void anIpv6SourceKeepsItsAddressAndPort() {
        AppSocket s = AppSocket.parse(AppSocket.Proto.TCP, "fd00::2#51000");

        assertEquals("fd00:0:0:0:0:0:0:2", s.ip().getHostAddress());
        assertEquals(51000, s.port());
        assertEquals("tcp:fd00::2#51000", s.toString());
    }

    @Test
    void anEmptyOrMalformedSourceNamesNoApp() {
        assertNull(AppSocket.parse(AppSocket.Proto.UDP, null));
        assertNull(AppSocket.parse(AppSocket.Proto.UDP, ""));
        assertNull(AppSocket.parse(AppSocket.Proto.UDP, "10.0.2.15"));
        assertNull(AppSocket.parse(AppSocket.Proto.UDP, "10.0.2.15#"));
        assertNull(AppSocket.parse(AppSocket.Proto.UDP, "example.com#53"));
        assertNull(AppSocket.parse(AppSocket.Proto.UDP, "10.0.2.15#0"));
        assertNull(AppSocket.parse(AppSocket.Proto.UDP, "10.0.2.15#65536"));
    }
}
