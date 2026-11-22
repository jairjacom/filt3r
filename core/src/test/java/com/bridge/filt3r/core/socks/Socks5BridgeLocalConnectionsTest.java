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

/**
 * With the LAN bypass on, connections to local addresses never reach the bridge. This counts the ones that do,
 * so a run on a phone can show whether the bypass works. Counting never changes what happens to a connection.
 */
class Socks5BridgeLocalConnectionsTest {

    private static final byte[] OUR_DNS = {10, 10, 10, 10};
    private static final int REP_FAILURE = 1;

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
    void aConnectionToAHomeNetworkAddressIsCountedAndStillSentOn() throws IOException {
        assertEquals(REP_FAILURE, connect(new byte[] {(byte) 192, (byte) 168, 12, 1}, 80)); // dialled, and the fake node fails
        assertEquals(1, bridge.localConnections());
    }

    @Test
    void everyPrivateIpv4RangeIsCountedAndItsNeighboursAreNot() throws IOException {
        connect(new byte[] {10, 1, 2, 3}, 80);
        connect(new byte[] {(byte) 172, 16, 0, 5}, 80);
        connect(new byte[] {(byte) 172, 31, (byte) 255, 1}, 80);
        connect(new byte[] {(byte) 169, (byte) 254, 1, 1}, 80);
        assertEquals(4, bridge.localConnections());

        connect(new byte[] {(byte) 172, 32, 0, 1}, 80); // just outside 172.16.0.0/12
        connect(new byte[] {(byte) 192, (byte) 169, 0, 1}, 80); // just outside 192.168.0.0/16
        connect(new byte[] {(byte) 198, 51, 100, 7}, 80);
        assertEquals(4, bridge.localConnections());
    }

    @Test
    void localIpv6AddressesAreCountedAndPublicOnesAreNot() throws IOException {
        connect(ipv6(0xfd, 0x00), 80);   // fc00::/7
        connect(ipv6(0xfe, 0x80), 80);   // fe80::/10
        assertEquals(2, bridge.localConnections());

        connect(ipv6(0x20, 0x01), 80);   // 2001:db8::-ish: public
        assertEquals(2, bridge.localConnections());
    }

    @Test
    void ourOwnDnsAddressIsNotCounted() throws IOException {
        connect(OUR_DNS, 853);
        assertEquals(0, bridge.localConnections());
    }

    private static byte[] ipv6(int first, int second) {
        byte[] ip = new byte[16];
        ip[0] = (byte) first;
        ip[1] = (byte) second;
        ip[15] = 1;
        return ip;
    }

    /** SOCKS5 greeting and login, then CONNECT to the IPv4 or IPv6 address; returns the reply code. */
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
            out.write(new byte[] {5, 1, 0, (byte) (ip.length == 4 ? 1 : 4)});
            out.write(ip);
            out.write(new byte[] {(byte) (port >> 8), (byte) port});
            out.flush();
            byte[] reply = new byte[2];
            in.readFully(reply); // arrives after the counter has been bumped
            return reply[1];
        }
    }
}
