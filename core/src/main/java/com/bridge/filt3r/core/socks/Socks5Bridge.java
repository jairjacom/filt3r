package com.bridge.filt3r.core.socks;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.PortUnreachableException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

/**
 * SOCKS5 server on loopback for hev-socks5-tunnel.
 *
 * <ul>
 *   <li>Listens on 127.0.0.1, random port.</li>
 *   <li>Requires RFC 1929 username/password; the credentials are random per instance.</li>
 *   <li>TCP CONNECT to IPv4 addresses goes straight to the destination over a protected socket.
 *       IPv6 and domain-name destinations are refused.</li>
 *   <li>UDP ASSOCIATE relays only DNS (the virtual DNS address, port 53) to one upstream server.
 *       All other UDP is dropped.</li>
 * </ul>
 */
public final class Socks5Bridge {

    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int UDP_HEADER = 10; // RSV(2) FRAG(1) ATYP(1) IPv4(4) PORT(2)
    private static final int DNS_MAX = 4096;
    private static final byte[] LOOPBACK = {127, 0, 0, 1};

    private static final int ATYP_IPV4 = 1;
    private static final int ATYP_DOMAIN = 3;
    private static final int ATYP_IPV6 = 4;

    private static final int CMD_CONNECT = 1;
    private static final int CMD_UDP_ASSOCIATE = 3;

    private static final int REP_OK = 0;
    private static final int REP_FAILURE = 1;
    private static final int REP_NOT_ALLOWED = 2;
    private static final int REP_HOST_UNREACHABLE = 4;
    private static final int REP_REFUSED = 5;
    private static final int REP_TTL_EXPIRED = 6;
    private static final int REP_CMD_UNSUPPORTED = 7;
    private static final int REP_ATYP_UNSUPPORTED = 8;

    private final SocketProtector protector;
    private final byte[] dnsVirtual;
    private final InetSocketAddress dnsUpstream;
    private final byte[] username;
    private final byte[] password;
    private final Set<Closeable> open = ConcurrentHashMap.newKeySet();

    private final AtomicLong authFailed = new AtomicLong();
    private final AtomicLong connectOk = new AtomicLong();
    private final AtomicLong connectFailed = new AtomicLong();
    private final AtomicLong dnsForwarded = new AtomicLong();
    private final AtomicLong udpDropped = new AtomicLong();

    private ServerSocket server;
    private ExecutorService pool;

    /**
     * @param dnsVirtual  IPv4 address (4 bytes) that the tunnel advertises as its DNS server
     * @param dnsUpstream where DNS queries are really sent
     */
    public Socks5Bridge(SocketProtector protector, byte[] dnsVirtual, InetSocketAddress dnsUpstream) {
        this.protector = protector;
        this.dnsVirtual = dnsVirtual.clone();
        this.dnsUpstream = dnsUpstream;
        SecureRandom random = new SecureRandom();
        this.username = randomHex(random);
        this.password = randomHex(random);
    }

    public synchronized void start() throws IOException {
        if (server != null) {
            throw new IllegalStateException("already started");
        }
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "socks5-bridge");
            t.setDaemon(true);
            return t;
        });
        server = new ServerSocket(0, 128, InetAddress.getByAddress(LOOPBACK));
        ServerSocket s = server;
        pool.execute(() -> acceptLoop(s));
    }

    public synchronized void stop() {
        if (server == null) {
            return;
        }
        closeQuietly(server);
        for (Closeable c : open) {
            closeQuietly(c);
        }
        open.clear();
        pool.shutdownNow();
        server = null;
    }

    public synchronized int getPort() {
        return server.getLocalPort();
    }

    public String getUsername() {
        return new String(username, StandardCharsets.US_ASCII);
    }

    public String getPassword() {
        return new String(password, StandardCharsets.US_ASCII);
    }

    /** Counters only; never contains addresses or credentials. */
    public String stats() {
        return "authFailed=" + authFailed + " connectOk=" + connectOk + " connectFailed=" + connectFailed
                + " dnsForwarded=" + dnsForwarded + " udpDropped=" + udpDropped;
    }

    private static byte[] randomHex(SecureRandom random) {
        byte[] raw = new byte[16];
        random.nextBytes(raw);
        StringBuilder sb = new StringBuilder(32);
        for (byte b : raw) {
            sb.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        }
        return sb.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private void acceptLoop(ServerSocket s) {
        while (!s.isClosed()) {
            try {
                Socket client = s.accept();
                open.add(client);
                pool.execute(() -> handle(client));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            client.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            if (!authenticate(in, out)) {
                authFailed.incrementAndGet();
                return;
            }
            if (in.readUnsignedByte() != 5) {
                return;
            }
            int cmd = in.readUnsignedByte();
            in.readUnsignedByte(); // RSV
            int atyp = in.readUnsignedByte();
            byte[] ipv4 = readAddress(in, atyp); // null unless IPv4
            int port = in.readUnsignedShort();
            if (cmd == CMD_UDP_ASSOCIATE) {
                udpAssociate(client, in, out);
            } else if (cmd == CMD_CONNECT) {
                connect(client, out, atyp, ipv4, port);
            } else {
                reply(out, REP_CMD_UNSUPPORTED, 0);
            }
        } catch (IOException | RuntimeException e) {
            // connection dropped or malformed request: just close
        } finally {
            open.remove(client);
            closeQuietly(client);
        }
    }

    /** RFC 1928 method selection, then RFC 1929 username/password. */
    private boolean authenticate(DataInputStream in, OutputStream out) throws IOException {
        if (in.readUnsignedByte() != 5) {
            return false;
        }
        byte[] methods = new byte[in.readUnsignedByte()];
        in.readFully(methods);
        boolean offered = false;
        for (byte m : methods) {
            offered |= m == 2;
        }
        if (!offered) {
            out.write(new byte[] {5, (byte) 0xFF});
            out.flush();
            return false;
        }
        out.write(new byte[] {5, 2});
        out.flush();

        if (in.readUnsignedByte() != 1) {
            return false;
        }
        byte[] user = new byte[in.readUnsignedByte()];
        in.readFully(user);
        byte[] pass = new byte[in.readUnsignedByte()];
        in.readFully(pass);
        // Non-short-circuit on purpose: both comparisons always run.
        boolean ok = MessageDigest.isEqual(user, username) & MessageDigest.isEqual(pass, password);
        out.write(new byte[] {1, (byte) (ok ? 0 : 1)});
        out.flush();
        return ok;
    }

    /** Reads the address field. Returns the 4 bytes for IPv4, null for IPv6 or a domain name. */
    private static byte[] readAddress(DataInputStream in, int atyp) throws IOException {
        switch (atyp) {
            case ATYP_IPV4: {
                byte[] a = new byte[4];
                in.readFully(a);
                return a;
            }
            case ATYP_IPV6:
                in.readFully(new byte[16]);
                return null;
            case ATYP_DOMAIN:
                in.readFully(new byte[in.readUnsignedByte()]);
                return null;
            default:
                throw new IOException("bad address type");
        }
    }

    private void connect(Socket client, OutputStream out, int atyp, byte[] ipv4, int port)
            throws IOException {
        if (ipv4 == null) {
            // IPv6 is blocked on purpose; domain names never arrive from hev (no mapdns).
            reply(out, atyp == ATYP_IPV6 ? REP_NOT_ALLOWED : REP_ATYP_UNSUPPORTED, 0);
            connectFailed.incrementAndGet();
            return;
        }
        Socket up = new Socket();
        open.add(up);
        try {
            try {
                if (!protector.protect(up)) {
                    throw new IOException("protect failed");
                }
                up.connect(new InetSocketAddress(InetAddress.getByAddress(ipv4), port), CONNECT_TIMEOUT_MS);
                up.setTcpNoDelay(true);
            } catch (IOException e) {
                connectFailed.incrementAndGet();
                reply(out, failureCode(e), 0);
                return;
            }
            connectOk.incrementAndGet();
            reply(out, REP_OK, 0);
            client.setSoTimeout(0);
            relay(client, up);
        } finally {
            open.remove(up);
            closeQuietly(up);
        }
    }

    private static int failureCode(IOException e) {
        if (e instanceof ConnectException) {
            return REP_REFUSED;
        }
        if (e instanceof SocketTimeoutException) {
            return REP_TTL_EXPIRED;
        }
        if (e instanceof NoRouteToHostException) {
            return REP_HOST_UNREACHABLE;
        }
        return REP_FAILURE;
    }

    /** Copies both directions until both sides are finished. */
    private void relay(Socket a, Socket b) {
        Future<?> back = pool.submit(() -> copy(b, a));
        copy(a, b);
        try {
            back.get();
        } catch (Exception e) {
            // interrupted by stop(): sockets are closed by the caller
        }
    }

    private static void copy(Socket src, Socket dst) {
        byte[] buf = new byte[16 * 1024];
        try {
            int n;
            while ((n = src.getInputStream().read(buf)) != -1) {
                dst.getOutputStream().write(buf, 0, n);
            }
            dst.shutdownOutput(); // clean EOF: pass it on, keep the other direction open
        } catch (IOException e) {
            closeQuietly(src);
            closeQuietly(dst);
        }
    }

    /**
     * UDP ASSOCIATE. The association lives as long as the TCP control connection stays open.
     * Only datagrams to the virtual DNS address, port 53, are forwarded.
     */
    private void udpAssociate(Socket control, DataInputStream in, OutputStream out) throws IOException {
        DatagramSocket relay = new DatagramSocket(0, InetAddress.getByAddress(LOOPBACK));
        DatagramSocket upstream = new DatagramSocket();
        open.add(relay);
        open.add(upstream);
        try {
            if (!protector.protect(upstream)) {
                reply(out, REP_FAILURE, 0);
                return;
            }
            upstream.connect(dnsUpstream);
            reply(out, REP_OK, relay.getLocalPort());
            control.setSoTimeout(0);

            UdpAssociation assoc = new UdpAssociation(relay, upstream);
            pool.execute(assoc::clientToUpstream);
            pool.execute(assoc::upstreamToClient);
            while (in.read() != -1) {
                // control connection carries nothing after the request; wait for it to close
            }
        } finally {
            open.remove(relay);
            open.remove(upstream);
            closeQuietly(relay);
            closeQuietly(upstream);
        }
    }

    private final class UdpAssociation {
        private final DatagramSocket relay;
        private final DatagramSocket upstream;
        private volatile SocketAddress client; // pinned to the first sender

        UdpAssociation(DatagramSocket relay, DatagramSocket upstream) {
            this.relay = relay;
            this.upstream = upstream;
        }

        void clientToUpstream() {
            byte[] buf = new byte[UDP_HEADER + DNS_MAX];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                while (true) {
                    p.setLength(buf.length);
                    relay.receive(p);
                    if (client == null) {
                        client = p.getSocketAddress();
                    } else if (!client.equals(p.getSocketAddress())) {
                        udpDropped.incrementAndGet();
                        continue;
                    }
                    if (!isDnsQuery(buf, p.getLength())) {
                        udpDropped.incrementAndGet();
                        continue;
                    }
                    try {
                        upstream.send(new DatagramPacket(buf, UDP_HEADER, p.getLength() - UDP_HEADER));
                        dnsForwarded.incrementAndGet();
                    } catch (PortUnreachableException e) {
                        udpDropped.incrementAndGet();
                    }
                }
            } catch (IOException e) {
                // socket closed: association is over
            }
        }

        private boolean isDnsQuery(byte[] b, int len) {
            if (len <= UDP_HEADER || b[0] != 0 || b[1] != 0 || b[2] != 0 || b[3] != ATYP_IPV4) {
                return false; // short, reserved bits set, fragmented, or not IPv4
            }
            int port = ((b[8] & 0xFF) << 8) | (b[9] & 0xFF);
            return port == 53 && Arrays.equals(Arrays.copyOfRange(b, 4, 8), dnsVirtual);
        }

        void upstreamToClient() {
            byte[] buf = new byte[UDP_HEADER + DNS_MAX];
            DatagramPacket p = new DatagramPacket(buf, UDP_HEADER, DNS_MAX);
            buf[3] = ATYP_IPV4;
            System.arraycopy(dnsVirtual, 0, buf, 4, 4);
            buf[8] = 0;
            buf[9] = 53;
            try {
                while (true) {
                    p.setLength(DNS_MAX);
                    try {
                        upstream.receive(p);
                    } catch (PortUnreachableException e) {
                        continue; // ICMP from an earlier send; keep the association
                    }
                    SocketAddress to = client;
                    if (to != null) {
                        relay.send(new DatagramPacket(buf, 0, UDP_HEADER + p.getLength(), to));
                    }
                }
            } catch (IOException e) {
                // socket closed: association is over
            }
        }
    }

    /** Success replies carry the loopback address and the given port (the UDP relay port). */
    private static void reply(OutputStream out, int code, int port) throws IOException {
        out.write(new byte[] {5, (byte) code, 0, ATYP_IPV4, 127, 0, 0, 1, (byte) (port >> 8), (byte) port});
        out.flush();
    }

    private static void closeQuietly(Closeable c) {
        try {
            c.close();
        } catch (IOException e) {
            // nothing to do
        }
    }
}
