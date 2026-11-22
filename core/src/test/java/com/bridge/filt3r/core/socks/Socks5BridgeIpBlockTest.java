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
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.bridge.filt3r.core.filter.IpBlockList;

/** A blocked IP is refused before any node is dialled, unless filtering is paused. */
class Socks5BridgeIpBlockTest {

    private static final int REP_NOT_ALLOWED = 2;
    private static final int REP_FAILURE = 1;

    private final AtomicInteger dials = new AtomicInteger();
    private final AtomicBoolean paused = new AtomicBoolean();
    private Socks5Bridge bridge;

    @BeforeEach
    void setUp() throws IOException {
        Dialer failing = (address, port, timeoutMs) -> {
            dials.incrementAndGet();
            throw new IOException("no node");
        };
        bridge = new Socks5Bridge(new byte[] {10, 0, 0, 2}, (client, query) -> new byte[0], failing, null, paused::get);
        bridge.setIpBlockList(IpBlockList.parse(new ByteArrayInputStream("203.0.113.5\n".getBytes(StandardCharsets.US_ASCII))));
        bridge.start();
    }

    @AfterEach
    void tearDown() {
        bridge.stop();
    }

    @Test
    void aBlockedIpIsRefusedAndNoNodeIsDialled() throws IOException {
        assertEquals(REP_NOT_ALLOWED, connectReply(new byte[] {(byte) 203, 0, 113, 5}));
        assertEquals(0, dials.get());
    }

    @Test
    void aBlockedIpGoesToTheNodeWhileFilteringIsPaused() throws IOException {
        paused.set(true);
        assertEquals(REP_FAILURE, connectReply(new byte[] {(byte) 203, 0, 113, 5})); // dialled, and the fake node fails
        assertEquals(1, dials.get());
    }

    @Test
    void anIpThatIsNotOnTheListIsDialled() throws IOException {
        assertEquals(REP_FAILURE, connectReply(new byte[] {(byte) 198, 51, 100, 7}));
        assertEquals(1, dials.get());
    }

    /** Does the SOCKS5 greeting and login, sends CONNECT to the IPv4 address, returns the reply code. */
    private int connectReply(byte[] ip) throws IOException {
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
            out.write(new byte[] {5, 1, 0, 1, ip[0], ip[1], ip[2], ip[3], 0x01, (byte) 0xBB});
            out.flush();
            byte[] reply = new byte[10];
            in.readFully(reply);
            return reply[1];
        }
    }
}
