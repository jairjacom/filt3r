package com.bridge.filt3r.core.socks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.bridge.filt3r.core.filter.IpBlockList;

/** DNS that goes around our filter over TCP is counted when the bridge sends it on to a node, and never otherwise. */
class Socks5BridgeDnsBypassTest {

    private static final byte[] OUR_DNS = {10, 10, 10, 10};
    private static final byte[] OTHER_DNS = {(byte) 198, 51, 100, 7};
    private static final byte[] BLOCKED = {(byte) 203, 0, 113, 5};
    private static final int REP_FAILURE = 1;
    private static final int REP_NOT_ALLOWED = 2;

    private final AtomicInteger dials = new AtomicInteger();
    private Socks5Bridge bridge;

    @BeforeEach
    void setUp() throws IOException {
        Dialer failing = (address, port, timeoutMs) -> {
            dials.incrementAndGet();
            throw new IOException("no node");
        };
        bridge = new Socks5Bridge(OUR_DNS, (client, query) -> new byte[0], failing, null, () -> false);
        bridge.setIpBlockList(IpBlockList.parse(new ByteArrayInputStream("203.0.113.5\n".getBytes(StandardCharsets.US_ASCII))));
        bridge.start();
    }

    @AfterEach
    void tearDown() {
        bridge.stop();
    }

    @Test
    void tcpPort53ToAnotherServerIsCountedAndStillSentToTheNode() throws IOException {
        assertEquals(REP_FAILURE, connect(OTHER_DNS, 53)); // dialled, and the fake node fails
        assertEquals(1, dials.get());
        assertEquals(1, bridge.dnsBypassTcp());
        assertEquals(0, bridge.dnsBypassTls());
    }

    @Test
    void dnsOverTlsToAnotherServerIsCounted() throws IOException {
        connect(OTHER_DNS, 853);
        assertEquals(0, bridge.dnsBypassTcp());
        assertEquals(1, bridge.dnsBypassTls());
    }

    @Test
    void androidsPrivateDnsProbeToOurOwnAddressIsRefusedAndNotCounted() throws IOException {
        assertEquals(REP_NOT_ALLOWED, connect(OUR_DNS, 853));
        assertEquals(0, bridge.dnsBypassTls());
        assertEquals(0, dials.get());
    }

    @Test
    void aBlockedAddressIsRefusedAndNotCounted() throws IOException {
        assertEquals(REP_NOT_ALLOWED, connect(BLOCKED, 53));
        assertEquals(0, bridge.dnsBypassTcp());
        assertEquals(0, dials.get());
    }

    @Test
    void otherPortsAreNotCounted() throws IOException {
        connect(OTHER_DNS, 443);
        assertEquals(0, bridge.dnsBypassTcp());
        assertEquals(0, bridge.dnsBypassTls());
    }

    /** SOCKS5 greeting and login, then CONNECT to the IPv4 address; returns the reply code. */
    private int connect(byte[] ip, int port) throws IOException {
        try (Socket s = new Socket(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), bridge.getPort())) {
            s.setSoTimeout(5_000);
            OutputStream out = s.getOutputStream();
            DataInputStream in = new DataInputStream(s.getInputStream());
            out.write(new byte[] {5, 1, 2});
            in.readFully(new byte[2]);
            byte[] user = bridge.getUsername().getBytes(StandardCharsets.US_ASCII);
            byte[] pass = bridge.getPassword().getBytes(StandardCharsets.US_ASCII);
            out.write(1);
            out.write(user.length);
            out.write(user);
            out.write(pass.length);
            out.write(pass);
            in.readFully(new byte[2]);
            out.write(new byte[] {5, 1, 0, 1, ip[0], ip[1], ip[2], ip[3], (byte) (port >> 8), (byte) port});
            out.flush();
            byte[] reply = new byte[2];
            in.readFully(reply); // arrives after the counter has been bumped
            return reply[1];
        }
    }
}
