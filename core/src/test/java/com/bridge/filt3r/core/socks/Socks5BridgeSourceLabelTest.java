package com.bridge.filt3r.core.socks;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * hev may add "#address#port" of the app's own socket after the bridge user name. The bridge hands it to
 * the DNS handler as the client label, and still accepts the plain name; any other name is refused.
 */
class Socks5BridgeSourceLabelTest {

    private static final byte[] OUR_DNS = {10, 10, 10, 10};
    private static final byte[] QUERY = new byte[12];

    private final AtomicReference<String> seen = new AtomicReference<>();
    private Socks5Bridge bridge;

    @BeforeEach
    void setUp() throws IOException {
        Dialer failing = (address, port, timeoutMs) -> {
            throw new IOException("no node");
        };
        bridge = new Socks5Bridge(OUR_DNS, (client, query) -> {
            seen.set(client);
            return new byte[12];
        }, failing, null, () -> false);
        bridge.start();
    }

    @AfterEach
    void tearDown() {
        bridge.stop();
    }

    @Test
    void thePlainUserNameKeepsTheTunnelLabel() throws IOException {
        assertEquals("tunnel", tcpDnsLabel(bridge.getUsername()));
    }

    @Test
    void aSourceAfterTheNameBecomesTheTcpLabel() throws IOException {
        assertEquals("tcp:10.0.2.15#40000", tcpDnsLabel(bridge.getUsername() + "#10.0.2.15#40000"));
    }

    @Test
    void aSourceAfterTheNameBecomesTheUdpLabel() throws IOException {
        try (Socket control = new Socket(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), bridge.getPort());
                DatagramSocket udp = new DatagramSocket()) {
            control.setSoTimeout(5_000);
            udp.setSoTimeout(5_000);
            DataInputStream in = new DataInputStream(control.getInputStream());
            OutputStream out = control.getOutputStream();
            assertEquals(0, login(in, out, bridge.getUsername() + "#10.0.2.15#40001"));
            out.write(new byte[] {5, 3, 0, 1, 0, 0, 0, 0, 0, 0}); // UDP ASSOCIATE
            out.flush();
            byte[] reply = new byte[10];
            in.readFully(reply);
            int relayPort = ((reply[8] & 0xFF) << 8) | (reply[9] & 0xFF);

            byte[] packet = new byte[10 + QUERY.length]; // RSV, FRAG, ATYP, address, port 53, then the query
            packet[3] = 1;
            System.arraycopy(OUR_DNS, 0, packet, 4, 4);
            packet[9] = 53;
            udp.send(new DatagramPacket(packet, packet.length, InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), relayPort));
            udp.receive(new DatagramPacket(new byte[512], 512)); // the answer arrives after the handler ran
        }
        assertEquals("udp:10.0.2.15#40001", seen.get());
    }

    @Test
    void aLongerOrShorterNameIsRefused() throws IOException {
        assertEquals(1, loginOnly(bridge.getUsername() + "X"));
        assertEquals(1, loginOnly(bridge.getUsername().substring(1)));
        assertEquals(1, loginOnly(bridge.getUsername() + "X#10.0.2.15#40000"));
    }

    /** CONNECT to the virtual DNS address over TCP, send one query, and return the label the handler saw. */
    private String tcpDnsLabel(String user) throws IOException {
        try (Socket s = new Socket(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), bridge.getPort())) {
            s.setSoTimeout(5_000);
            DataInputStream in = new DataInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            assertEquals(0, login(in, out, user));
            out.write(new byte[] {5, 1, 0, 1, OUR_DNS[0], OUR_DNS[1], OUR_DNS[2], OUR_DNS[3], 0, 53});
            out.flush();
            in.readFully(new byte[10]); // the reply
            out.write(new byte[] {0, (byte) QUERY.length});
            out.write(QUERY);
            out.flush();
            in.readFully(new byte[2 + 12]); // the answer arrives after the handler ran
        }
        return seen.get();
    }

    private int loginOnly(String user) throws IOException {
        try (Socket s = new Socket(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), bridge.getPort())) {
            s.setSoTimeout(5_000);
            return login(new DataInputStream(s.getInputStream()), s.getOutputStream(), user);
        }
    }

    /** Method selection plus RFC 1929 login with the bridge's password; returns the status byte (0 = accepted). */
    private int login(DataInputStream in, OutputStream out, String user) throws IOException {
        out.write(new byte[] {5, 1, 2});
        in.readFully(new byte[2]);
        byte[] name = user.getBytes(StandardCharsets.US_ASCII);
        byte[] pass = bridge.getPassword().getBytes(StandardCharsets.US_ASCII);
        out.write(1);
        out.write(name.length);
        out.write(name);
        out.write(pass.length);
        out.write(pass);
        out.flush();
        byte[] status = new byte[2];
        in.readFully(status);
        return status[1];
    }
}
