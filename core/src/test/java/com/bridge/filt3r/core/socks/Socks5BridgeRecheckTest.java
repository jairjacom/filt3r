package com.bridge.filt3r.core.socks;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.bridge.filt3r.core.firewall.Verdict;

/** A connection that is already open is asked about again when a rule changes, and closed if it is now refused. */
class Socks5BridgeRecheckTest {

    private static final byte[] OUR_DNS = {10, 0, 0, 2};
    private static final byte[] OTHER_IP = {(byte) 198, 51, 100, 7};

    /** A node link that carries nothing and ends when it is closed. */
    private static final class IdleLink implements Link {
        final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public InputStream in() {
            return new InputStream() {
                @Override
                public int read() throws IOException {
                    try {
                        closed.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return -1;
                }
            };
        }

        @Override
        public OutputStream out() {
            return new OutputStream() {
                @Override
                public void write(int b) {
                }
            };
        }

        @Override
        public void setReadTimeout(int ms) {
        }

        @Override
        public void shutdownOutput() {
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private final IdleLink link = new IdleLink();
    private final AtomicReference<Verdict> decision = new AtomicReference<>(Verdict.ALLOW);
    private final AtomicInteger directDials = new AtomicInteger();
    private final List<String> reasons = Collections.synchronizedList(new ArrayList<>());
    private Socks5Bridge bridge;

    @BeforeEach
    void setUp() throws IOException {
        Dialer direct = (address, port, timeoutMs) -> {
            directDials.incrementAndGet();
            return link;
        };
        bridge = new Socks5Bridge(OUR_DNS, (client, query) -> new byte[0], (address, port, timeoutMs) -> link, direct, () -> false);
        bridge.setConnectionGate((client, dest, port) -> decision.get());
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
    void anOpenConnectionTheGateNowRefusesIsClosedAndReported() throws Exception {
        try (Socket s = open("#10.0.2.15#40000")) {
            assertEquals(0, bridge.recheckOpenConnections()); // still allowed
            assertTrue(link.closed.getCount() == 1);

            decision.set(Verdict.refuse("Device locked"));
            assertEquals(1, bridge.recheckOpenConnections());
            assertTrue(link.closed.await(5, TimeUnit.SECONDS), "the link to the node must be closed");
            assertEquals(List.of("Device locked"), reasons);
        }
    }

    @Test
    void anOpenExemptConnectionIsClosedWhenItsRuleNowRefusesAndKeptWhileItIsDialledDirectly() throws Exception {
        decision.set(Verdict.DIRECT);
        try (Socket s = open("#10.0.2.15#40002")) {
            assertEquals(1, directDials.get());
            assertEquals(0, bridge.recheckOpenConnections()); // the rule does not refuse it: it stays
            assertEquals(1, link.closed.getCount());

            decision.set(Verdict.refuse("App rule"));
            assertEquals(1, bridge.recheckOpenConnections());
            assertTrue(link.closed.await(5, TimeUnit.SECONDS), "the direct link must be closed");
            assertEquals(List.of("App rule"), reasons);
        }
    }

    @Test
    void anAllowedOpenConnectionStaysOpen() throws Exception {
        try (Socket s = open("#10.0.2.15#40001")) {
            assertEquals(0, bridge.recheckOpenConnections());
            assertEquals(1, link.closed.getCount());
        }
    }

    /** Logs in with the source, sends CONNECT to OTHER_IP:443, reads the reply (must be success) and keeps the socket open. */
    private Socket open(String source) throws IOException {
        Socket s = new Socket(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), bridge.getPort());
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
        assertEquals(0, reply[1], "the connection must be accepted first");
        return s;
    }
}
