package com.bridge.filt3r.core.socks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** DNS that goes around our filter over TCP is counted, and counting never changes what happens to it. */
class Socks5BridgeDnsBypassTest {

    private static final byte[] OUR_DNS = {10, 10, 10, 10};
    private static final byte[] OTHER_DNS = {(byte) 198, 51, 100, 7};

    private Socks5Bridge bridge;

    @BeforeEach
    void setUp() throws IOException {
        Dialer failing = (address, port, timeoutMs) -> {
            throw new IOException("no node");
        };
        bridge = new Socks5Bridge(OUR_DNS, (client, query) -> new byte[0], failing, null, () -> false);
        bridge.start();
    }

    @AfterEach
    void tearDown() {
        bridge.stop();
    }

    @Test
    void tcpPort53ToAnotherServerIsCounted() throws IOException {
        connect(OTHER_DNS, 53);
        assertEquals(1, bridge.otherDnsTcp());
        assertEquals(0, bridge.otherDnsTls());
    }

    @Test
    void dnsOverTlsToAnotherServerIsCounted() throws IOException {
        connect(OTHER_DNS, 853);
        assertEquals(0, bridge.otherDnsTcp());
        assertEquals(1, bridge.otherDnsTls());
    }

    @Test
    void androidsPrivateDnsProbeToOurOwnAddressIsNotCounted() throws IOException {
        connect(OUR_DNS, 853); // refused by the bridge: it never leaves the tunnel
        assertEquals(0, bridge.otherDnsTls());
    }

    @Test
    void otherPortsAreNotCounted() throws IOException {
        connect(OTHER_DNS, 443);
        assertEquals(0, bridge.otherDnsTcp());
        assertEquals(0, bridge.otherDnsTls());
    }

    /** SOCKS5 greeting and login, then CONNECT to the IPv4 address; waits for the reply. */
    private void connect(byte[] ip, int port) throws IOException {
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
            in.readFully(new byte[2]); // the reply arrives after the counter has been bumped
        }
    }
}
