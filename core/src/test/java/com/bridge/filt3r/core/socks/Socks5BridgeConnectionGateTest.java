package com.bridge.filt3r.core.socks;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.bridge.filt3r.core.filter.IpBlockList;
import com.bridge.filt3r.core.firewall.FirewallPolicy;

/** The firewall gate runs before the bridge dials, and a refusal never reaches a node. */
class Socks5BridgeConnectionGateTest {

    private static final byte[] OUR_DNS = {10, 0, 0, 2};
    private static final byte[] OTHER_IP = {(byte) 198, 51, 100, 7};

    private final AtomicInteger dials = new AtomicInteger();
    private final AtomicInteger directDials = new AtomicInteger();
    private final List<String> reasons = Collections.synchronizedList(new ArrayList<>());
    private Socks5Bridge bridge;

    @BeforeEach
    void setUp() throws IOException {
        Dialer failing = (address, port, timeoutMs) -> {
            dials.incrementAndGet();
            throw new IOException("destination unreachable");
        };
        Dialer direct = (address, port, timeoutMs) -> {
            directDials.incrementAndGet();
            throw new IOException("destination unreachable");
        };
        bridge = new Socks5Bridge(OUR_DNS, (client, query) -> new byte[0], failing, direct, () -> false);
        bridge.setConnectionListener((client, dest, port, blocked, reason) -> {
            if (blocked) {
                reasons.add(reason);
            }
        });
        bridge.start();
    }

    @AfterEach
    void tearDown() {
        bridge.stop();
    }

    @Test
    void aRefusedFlowIsAnsweredNotAllowedReportedWithTheReasonAndNeverDialled() throws IOException {
        bridge.setConnectionGate((client, dest, port) -> ConnectionGate.Decision.refuse(FirewallPolicy.REASON_APP_RULE));

        assertEquals(2, connect("#10.0.2.15#40000")); // REP_NOT_ALLOWED
        assertEquals(0, dials.get());
        assertEquals(List.of(FirewallPolicy.REASON_APP_RULE), reasons);
    }

    @Test
    void aFlowTheGateLetsThroughIsDialled() throws IOException {
        bridge.setConnectionGate((client, dest, port) -> ConnectionGate.Decision.ALLOW);

        connect("#10.0.2.15#40001");
        assertEquals(1, dials.get());
        assertTrue(reasons.isEmpty());
    }

    @Test
    void aGateThatThrowsRefusesTheFlow() throws IOException {
        bridge.setConnectionGate((client, dest, port) -> {
            throw new IllegalStateException("boom");
        });

        assertEquals(2, connect("#10.0.2.15#40002"));
        assertEquals(0, dials.get());
        assertEquals(List.of(FirewallPolicy.REASON_ERROR), reasons);
    }

    @Test
    void theGateSeesTheAppSocketAndTheDestination() throws IOException {
        List<String> got = Collections.synchronizedList(new ArrayList<>());
        bridge.setConnectionGate((client, dest, port) -> {
            got.add(client + " " + dest.getHostAddress() + " " + port);
            return ConnectionGate.Decision.ALLOW;
        });

        connect("#10.0.2.15#40003");
        assertEquals(List.of("tcp:10.0.2.15#40003 198.51.100.7 443"), got);
    }

    @Test
    void aDirectFlowIsDialledWithTheDirectDialerAndNeverTheNode() throws IOException {
        bridge.setConnectionGate((client, dest, port) -> ConnectionGate.Decision.DIRECT);

        connect("#10.0.2.15#40004");
        assertEquals(0, dials.get());
        assertEquals(1, directDials.get());
        assertTrue(reasons.isEmpty());
    }

    @Test
    void aDirectFlowIsNotFilteredByTheIpBlockList() throws IOException {
        bridge.setIpBlockList(IpBlockList.parse(new ByteArrayInputStream("198.51.100.7\n".getBytes(StandardCharsets.US_ASCII))));

        bridge.setConnectionGate((client, dest, port) -> ConnectionGate.Decision.ALLOW);
        assertEquals(2, connect("#10.0.2.15#40005")); // an ordinary flow to a listed IP is refused
        assertEquals(List.of(ConnectionListener.REASON_IP_LIST), reasons);

        bridge.setConnectionGate((client, dest, port) -> ConnectionGate.Decision.DIRECT);
        connect("#10.0.2.15#40006");
        assertEquals(1, directDials.get()); // an Exempt app's flow is not
    }

    @Test
    void aRefusedExemptFlowIsRefusedLikeAnyOtherAndDialsNothing() throws IOException {
        bridge.setConnectionGate((client, dest, port) -> ConnectionGate.Decision.refuse(FirewallPolicy.REASON_APP_RULE));

        assertEquals(2, connect("#10.0.2.15#40007"));
        assertEquals(0, dials.get());
        assertEquals(0, directDials.get());
    }

    /** Greeting, login with the source after the user name, then CONNECT to OTHER_IP port 443; returns the reply code. */
    private int connect(String source) throws IOException {
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
            out.write(new byte[] {5, 1, 0, 1});
            out.write(OTHER_IP);
            out.write(new byte[] {0x01, (byte) 0xBB});
            out.flush();
            byte[] reply = new byte[10];
            in.readFully(reply);
            return reply[1];
        }
    }
}
