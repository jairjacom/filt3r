package com.bridge.filt3r.core.socks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.bridge.filt3r.core.filter.IpBlockList;

/** The bridge tells its listener each TCP connection and the verdict, before it answers hev. */
class Socks5BridgeConnectionListenerTest {

    private static final byte[] OUR_DNS = {10, 0, 0, 2};
    private static final byte[] BLOCKED_IP = {(byte) 203, 0, 113, 5};
    private static final byte[] OTHER_IP = {(byte) 198, 51, 100, 7};

    /** One reported connection. */
    private static final class Seen {
        final AppSocket client;
        final InetAddress dest;
        final int port;
        final boolean blocked;
        final String reason;

        Seen(AppSocket client, InetAddress dest, int port, boolean blocked, String reason) {
            this.client = client;
            this.dest = dest;
            this.port = port;
            this.blocked = blocked;
            this.reason = reason;
        }
    }

    private final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());
    private final AtomicBoolean paused = new AtomicBoolean();
    private Socks5Bridge bridge;

    @BeforeEach
    void setUp() throws IOException {
        Dialer failing = (address, port, timeoutMs) -> {
            throw new IOException("no node");
        };
        bridge = new Socks5Bridge(OUR_DNS, (client, query) -> new byte[0], failing, null, paused::get);
        bridge.setIpBlockList(IpBlockList.parse(new ByteArrayInputStream("203.0.113.5\n".getBytes(StandardCharsets.US_ASCII))));
        bridge.setConnectionListener((client, dest, port, blocked, reason) ->
                seen.add(new Seen(client, dest, port, blocked, reason)));
        bridge.start();
    }

    @AfterEach
    void tearDown() {
        bridge.stop();
    }

    @Test
    void aBlockedIpIsReportedBlockedWithTheReasonAndTheApp() throws IOException {
        connect("#10.0.2.15#40000", BLOCKED_IP);

        assertEquals(1, seen.size());
        Seen s = seen.get(0);
        assertTrue(s.blocked);
        assertEquals(ConnectionListener.REASON_IP_LIST, s.reason);
        assertEquals("203.0.113.5", s.dest.getHostAddress());
        assertEquals(443, s.port);
        assertEquals("tcp:10.0.2.15#40000", s.client.toString());
    }

    @Test
    void anAllowedConnectionIsReportedAllowedWithNoReason() throws IOException {
        connect("#10.0.2.15#40001", OTHER_IP);

        assertEquals(1, seen.size());
        assertFalse(seen.get(0).blocked);
        assertNull(seen.get(0).reason);
    }

    @Test
    void aBlockedIpIsReportedAllowedWhileFilteringIsPaused() throws IOException {
        paused.set(true);
        connect("", BLOCKED_IP);

        assertEquals(1, seen.size());
        assertFalse(seen.get(0).blocked);
    }

    @Test
    void whenHevNamesNoAppTheClientIsNull() throws IOException {
        connect("", OTHER_IP);

        assertEquals(1, seen.size());
        assertNull(seen.get(0).client);
    }

    @Test
    void anIpv6ConnectionIsReportedBlockedWhileIpv6IsOff() throws IOException {
        bridge.setAllowIPv6(false);
        byte[] v6 = new byte[16];
        v6[0] = 0x20;
        v6[1] = 0x01;
        v6[15] = 1;
        connect("#10.0.2.15#40002", v6);

        assertEquals(1, seen.size());
        assertTrue(seen.get(0).blocked);
        assertEquals(ConnectionListener.REASON_IPV6_OFF, seen.get(0).reason);
    }

    @Test
    void theTunnelsOwnDnsAddressIsNotReported() throws IOException {
        connect("", OUR_DNS);

        assertTrue(seen.isEmpty());
    }

    @Test
    void aListenerThatThrowsDoesNotBreakTheConnection() throws IOException {
        bridge.setConnectionListener((client, dest, port, blocked, reason) -> {
            throw new IllegalStateException("boom");
        });

        assertEquals(2, connect("", BLOCKED_IP)); // still refused, not dropped
    }

    /** Greeting, login with the source after the user name, then CONNECT to ip port 443; returns the reply code. */
    private int connect(String source, byte[] ip) throws IOException {
        try (Socket s = new Socket(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), bridge.getPort())) {
            s.setSoTimeout(5_000);
            OutputStream out = s.getOutputStream();
            DataInputStream in = new DataInputStream(s.getInputStream());
            out.write(new byte[] {5, 1, 2});
            in.readFully(new byte[2]);
            byte[] user = (bridge.getUsername() + source).getBytes(StandardCharsets.US_ASCII);
            byte[] pass = bridge.getPassword().getBytes(StandardCharsets.US_ASCII);
            out.write(1);
            out.write(user.length);
            out.write(user);
            out.write(pass.length);
            out.write(pass);
            in.readFully(new byte[2]);
            out.write(new byte[] {5, 1, 0, ip.length == 4 ? (byte) 1 : (byte) 4});
            out.write(ip);
            out.write(new byte[] {0x01, (byte) 0xBB});
            out.flush();
            byte[] reply = new byte[10]; // the bridge always answers with an IPv4-form bound address
            in.readFully(reply);
            return reply[1];
        }
    }
}
