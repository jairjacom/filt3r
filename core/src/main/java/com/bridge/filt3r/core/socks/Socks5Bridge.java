package com.bridge.filt3r.core.socks;

import com.bridge.filt3r.core.filter.IpBlockList;
import com.bridge.filt3r.core.firewall.FirewallPolicy;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NoRouteToHostException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * SOCKS5 server on loopback for hev-socks5-tunnel.
 *
 * <ul>
 *   <li>Listens on 127.0.0.1, random port.</li>
 *   <li>Requires RFC 1929 username/password; the credentials are random per instance.</li>
 *   <li>TCP CONNECT to IPv4 and IPv6 addresses goes straight to the destination over a protected
 *       socket. Domain-name destinations are refused.</li>
 *   <li>UDP ASSOCIATE answers only DNS (the virtual DNS address, port 53) through the DnsHandler.
 *       All other UDP is dropped.</li>
 * </ul>
 */
public final class Socks5Bridge {

    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int UDP_HEADER = 10; // RSV(2) FRAG(1) ATYP(1) IPv4(4) PORT(2)
    private static final int DNS_MAX = 4096;
    private static final int DNS_TCP_IDLE_MS = 30_000;
    private static final byte[] LOOPBACK = {127, 0, 0, 1};

    private static final int ATYP_IPV4 = 1;
    private static final int ATYP_DOMAIN = 3;
    private static final int ATYP_IPV6 = 4;

    private static final int CMD_CONNECT = 1;
    private static final int CMD_UDP_ASSOCIATE = 3;

    private static final int REP_OK = 0;
    private static final int REP_FAILURE = 1;
    private static final int REP_NOT_ALLOWED = 2;
    private static final int REP_NET_UNREACHABLE = 3;
    private static final int REP_HOST_UNREACHABLE = 4;
    private static final int REP_REFUSED = 5;
    private static final int REP_TTL_EXPIRED = 6;
    private static final int REP_CMD_UNSUPPORTED = 7;
    private static final int REP_ATYP_UNSUPPORTED = 8;

    private final Dialer dialer;
    private final Dialer fallback;
    private final BooleanSupplier paused;
    private final byte[] dnsVirtual;
    private final DnsHandler dns;
    private final byte[] username;
    private final byte[] password;
    private final Set<Closeable> open = ConcurrentHashMap.newKeySet();

    private final AtomicLong authFailed = new AtomicLong();
    private final AtomicLong connectOk = new AtomicLong();
    private final AtomicLong connectFailed = new AtomicLong();
    private final AtomicLong refusedDomain = new AtomicLong();
    private final AtomicLong refusedFirewall = new AtomicLong();
    private final AtomicLong refusedIpBlock = new AtomicLong();
    private final AtomicLong refusedIPv6 = new AtomicLong();
    private final AtomicLong dnsBypassTcp = new AtomicLong();
    private final AtomicLong dnsBypassTls = new AtomicLong();
    private final AtomicLong localConnections = new AtomicLong();
    private volatile boolean allowIPv6 = true;
    private final AtomicLong failedOpen = new AtomicLong();
    private volatile FailureMode failureMode = FailureMode.FAIL_CLOSED;
    private volatile String lastConnectError = "none";
    private volatile String lastDnsError = "none"; // DNS handlers never put query names in exceptions
    private final AtomicLong dnsForwarded = new AtomicLong();
    private final AtomicLong udpDropped = new AtomicLong();

    private ServerSocket server;
    private ExecutorService pool;
    private ExecutorService dnsPool;
    private static final int MAX_DNS_THREADS = 32;
    private static final int DNS_QUEUE = 64;

    /**
     * @param dnsVirtual IPv4 address (4 bytes) that the tunnel advertises as its DNS server
     * @param dns        answers the DNS queries that arrive for that address
     * @param dialer     opens the outgoing connection for each tunnelled TCP connection
     * @param fallback   used instead of {@code dialer} when it fails and the mode is FAIL_OPEN; may be null
     * @param paused     true while filtering is paused: the IP blocklist is then not applied
     */
    public Socks5Bridge(byte[] dnsVirtual, DnsHandler dns, Dialer dialer, Dialer fallback, BooleanSupplier paused) {
        this.dnsVirtual = dnsVirtual.clone();
        this.dns = dns;
        this.dialer = dialer;
        this.fallback = fallback;
        this.paused = paused;
        SecureRandom random = new SecureRandom();
        this.username = randomHex(random);
        this.password = randomHex(random);
    }

    /** Destinations on this list are refused before any node is dialled. Null = off. */
    private volatile IpBlockList ipBlock;

    public void setIpBlockList(IpBlockList list) {
        this.ipBlock = list;
    }

    private volatile ConnectionListener connectionListener;

    /** Told about every tunnelled TCP connection and its verdict. Null = nobody. */
    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }

    private volatile ConnectionGate gate;

    /** Asked before every tunnelled TCP connection is dialled; a reason refuses it. Null = no firewall. */
    public void setConnectionGate(ConnectionGate gate) {
        this.gate = gate;
    }

    public void setFailureMode(FailureMode mode) {
        this.failureMode = mode;
    }

    /** When off, IPv6 destinations are refused immediately. */
    public void setAllowIPv6(boolean allow) {
        this.allowIPv6 = allow;
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
        // Answering can block for tens of seconds, so a flood of queries must not mean a flood of threads.
        ThreadPoolExecutor answers = new ThreadPoolExecutor(MAX_DNS_THREADS, MAX_DNS_THREADS,
                30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(DNS_QUEUE), r -> {
            Thread t = new Thread(r, "socks5-dns");
            t.setDaemon(true);
            return t;
        });
        answers.allowCoreThreadTimeOut(true);
        dnsPool = answers;
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
        dnsPool.shutdownNow();
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

    /** TCP connections to port 53 of an address other than our DNS that the bridge sent on to a node: DNS that skips the filter. */
    public long dnsBypassTcp() {
        return dnsBypassTcp.get();
    }

    /** TCP connections to port 853 (DNS-over-TLS) of an address other than our DNS that the bridge sent on to a node: DNS that skips the filter. */
    public long dnsBypassTls() {
        return dnsBypassTls.get();
    }

    /** Connections to private or link-local addresses that reached the bridge. With the LAN bypass on, these should stay at 0. */
    public long localConnections() {
        return localConnections.get();
    }

    /** Counters only; never contains addresses or credentials. */
    public String stats() {
        return "authFailed=" + authFailed + " connectOk=" + connectOk + " connectFailed=" + connectFailed
                + " failedOpen=" + failedOpen + " mode=" + failureMode + " refusedDomain=" + refusedDomain
                + " refusedIPv6=" + refusedIPv6 + " refusedIpBlock=" + refusedIpBlock + " refusedFirewall=" + refusedFirewall
                + " dnsBypassTcp=" + dnsBypassTcp + " dnsBypassTls=" + dnsBypassTls
                + " localConnections=" + localConnections
                + " lastConnectError=" + lastConnectError
                + " lastDnsError=" + lastDnsError
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
            String source = authenticate(in, out);
            if (source == null) {
                authFailed.incrementAndGet();
                return;
            }
            if (in.readUnsignedByte() != 5) {
                return;
            }
            int cmd = in.readUnsignedByte();
            in.readUnsignedByte(); // RSV
            int atyp = in.readUnsignedByte();
            byte[] ip = readAddress(in, atyp);
            int port = in.readUnsignedShort();
            if (cmd == CMD_UDP_ASSOCIATE) {
                udpAssociate(client, in, out, AppSocket.parse(AppSocket.Proto.UDP, source));
            } else if (cmd == CMD_CONNECT) {
                connect(client, out, ip, port, source);
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

    /**
     * RFC 1928 method selection, then RFC 1929 username/password. The user name is the bridge's
     * name, optionally followed by '#' and the app-side "address#port" of the flow (hev patch).
     * Returns that source text ("" when absent), or null when authentication failed.
     */
    private String authenticate(DataInputStream in, OutputStream out) throws IOException {
        if (in.readUnsignedByte() != 5) {
            return null;
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
            return null;
        }
        out.write(new byte[] {5, 2});
        out.flush();

        if (in.readUnsignedByte() != 1) {
            return null;
        }
        byte[] user = new byte[in.readUnsignedByte()];
        in.readFully(user);
        byte[] pass = new byte[in.readUnsignedByte()];
        in.readFully(pass);
        boolean named = user.length == username.length
                || (user.length > username.length && user[username.length] == '#');
        // Non-short-circuit on purpose: all comparisons always run.
        boolean ok = named
                & MessageDigest.isEqual(Arrays.copyOf(user, username.length), username)
                & MessageDigest.isEqual(pass, password);
        out.write(new byte[] {1, (byte) (ok ? 0 : 1)});
        out.flush();
        if (!ok) {
            return null;
        }
        return user.length == username.length ? ""
                : new String(user, username.length + 1, user.length - username.length - 1, StandardCharsets.US_ASCII);
    }

    /** Reads the address field. Returns 4 bytes (IPv4), 16 bytes (IPv6), or null for a domain name. */
    private static byte[] readAddress(DataInputStream in, int atyp) throws IOException {
        switch (atyp) {
            case ATYP_IPV4: {
                byte[] a = new byte[4];
                in.readFully(a);
                return a;
            }
            case ATYP_IPV6: {
                byte[] a = new byte[16];
                in.readFully(a);
                return a;
            }
            case ATYP_DOMAIN:
                in.readFully(new byte[in.readUnsignedByte()]);
                return null;
            default:
                throw new IOException("bad address type");
        }
    }

    private void connect(Socket client, OutputStream out, byte[] ip, int port, String source) throws IOException {
        if (ip == null) {
            // Domain names never arrive from hev (no mapdns): refuse, fail closed.
            reply(out, REP_ATYP_UNSUPPORTED, 0);
            refusedDomain.incrementAndGet();
            return;
        }
        if (ip.length == 4 && Arrays.equals(ip, dnsVirtual)) {
            // The virtual DNS address exists only inside the tunnel. Sending it to a node would
            // leak the query in plain text and skip the filter, so it is answered here or refused.
            if (port != 53) {
                reply(out, REP_NOT_ALLOWED, 0);
                return;
            }
            reply(out, REP_OK, 0);
            client.setSoTimeout(DNS_TCP_IDLE_MS);
            dnsOverTcp(client, out, AppSocket.parse(AppSocket.Proto.TCP, source));
            return;
        }
        ConnectionGate firewall = gate;
        if (firewall != null) {
            String why;
            try {
                why = firewall.refuse(AppSocket.parse(AppSocket.Proto.TCP, source), InetAddress.getByAddress(ip), port);
            } catch (RuntimeException e) {
                why = FirewallPolicy.REASON_ERROR; // an unexpected error blocks, as in RethinkDNS
            }
            if (why != null) {
                report(source, InetAddress.getByAddress(ip), port, true, why);
                reply(out, REP_NOT_ALLOWED, 0);
                refusedFirewall.incrementAndGet();
                return;
            }
        }
        if (isLocalAddress(ip)) {
            localConnections.incrementAndGet();
        }
        if (ip.length == 16 && !allowIPv6) {
            // The node has no IPv6 exit: fail at once so the app falls back to IPv4 without waiting.
            report(source, InetAddress.getByAddress(ip), port, true, ConnectionListener.REASON_IPV6_OFF);
            reply(out, REP_NET_UNREACHABLE, 0);
            refusedIPv6.incrementAndGet();
            return;
        }
        InetAddress addr = InetAddress.getByAddress(ip);
        IpBlockList blockedIps = ipBlock;
        if (blockedIps != null && !paused.getAsBoolean()
                && blockedIps.isBlocked(addr)) {
            // Refused before any node is dialled, so the FAIL_OPEN fallback cannot reach it either.
            report(source, addr, port, true, ConnectionListener.REASON_IP_LIST);
            reply(out, REP_NOT_ALLOWED, 0);
            refusedIpBlock.incrementAndGet();
            return;
        }
        if (port == 53) {
            dnsBypassTcp.incrementAndGet();
        } else if (port == 853) {
            dnsBypassTls.incrementAndGet();
        }
        Link up;
        try {
            up = dialer.dial(addr, port, CONNECT_TIMEOUT_MS);
        } catch (IOException e) {
            connectFailed.incrementAndGet();
            lastConnectError = e.getClass().getSimpleName();
            up = null;
            Dialer fb = fallback;
            // "Connect directly" means: when no node works. One failed connection to a node that is
            // otherwise fine stays a failure (the node is counted, and after 3 it is replaced).
            if (failureMode == FailureMode.FAIL_OPEN && fb != null && e instanceof NoNodeException) {
                try {
                    up = fb.dial(addr, port, CONNECT_TIMEOUT_MS);
                    failedOpen.incrementAndGet();
                } catch (IOException e2) {
                    lastConnectError = e2.getClass().getSimpleName();
                }
            }
            if (up == null) {
                boolean noNode = e instanceof NoNodeException;
                report(source, addr, port, noNode, noNode ? ConnectionListener.REASON_NO_NODE : null);
                reply(out, failureCode(e), 0);
                return;
            }
        }
        report(source, addr, port, false, null); // allowed: a node, or the direct fallback, accepted it
        open.add(up);
        try {
            connectOk.incrementAndGet();
            reply(out, REP_OK, 0);
            client.setSoTimeout(0);
            relay(new SocketLink(client), up);
        } finally {
            open.remove(up);
            closeQuietly(up);
        }
    }

    /** Tells the listener, if any. A listener that fails must not break the connection it was told about. */
    private void report(String source, InetAddress dest, int port, boolean blocked, String reason) {
        ConnectionListener listener = connectionListener;
        if (listener == null) {
            return;
        }
        try {
            listener.onConnection(AppSocket.parse(AppSocket.Proto.TCP, source), dest, port, blocked, reason);
        } catch (RuntimeException e) {
            // logging is best effort
        }
    }

    /** DNS over TCP (RFC 1035 4.2.2): 2-byte length, then the message; any number of queries per connection. */
    private void dnsOverTcp(Socket client, OutputStream out, AppSocket label) throws IOException {
        DataInputStream in = new DataInputStream(client.getInputStream());
        while (true) { // ends with an exception: EOF, idle timeout or a bad length
            int len = in.readUnsignedShort();
            if (len == 0 || len > DNS_MAX) {
                throw new IOException("bad DNS length");
            }
            byte[] query = new byte[len];
            in.readFully(query);
            Future<byte[]> answer;
            try {
                answer = dnsPool.submit(() -> dns.handle(label, query)); // same bounded pool as UDP
            } catch (RejectedExecutionException e) {
                udpDropped.incrementAndGet();
                throw new IOException("DNS busy");
            }
            byte[] response;
            try {
                response = answer.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted");
            } catch (ExecutionException e) {
                lastDnsError = e.getCause().getClass().getSimpleName() + ": " + e.getCause().getMessage();
                throw new IOException("DNS failed");
            }
            if (response == null || response.length > 0xFFFF) {
                throw new IOException("no DNS answer");
            }
            byte[] framed = new byte[2 + response.length];
            framed[0] = (byte) (response.length >> 8);
            framed[1] = (byte) response.length;
            System.arraycopy(response, 0, framed, 2, response.length);
            out.write(framed);
            out.flush();
            dnsForwarded.incrementAndGet();
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
    private void relay(Link a, Link b) {
        Future<?> back = pool.submit(() -> copy(b, a));
        copy(a, b);
        try {
            back.get();
        } catch (Exception e) {
            // interrupted by stop(): sockets are closed by the caller
        }
    }

    /** 10/8, 172.16/12, 192.168/16 and 169.254/16 for IPv4; fc00::/7 and fe80::/10 for IPv6. */
    private static boolean isLocalAddress(byte[] ip) {
        int a = ip[0] & 0xFF;
        int b = ip[1] & 0xFF;
        if (ip.length == 4) {
            return a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168) || (a == 169 && b == 254);
        }
        return (a & 0xFE) == 0xFC || (a == 0xFE && (b & 0xC0) == 0x80);
    }

    private static void copy(Link src, Link dst) {
        byte[] buf = new byte[16 * 1024];
        try {
            InputStream in = src.in();
            OutputStream out = dst.out();
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
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
    private void udpAssociate(Socket control, DataInputStream in, OutputStream out, AppSocket label) throws IOException {
        DatagramSocket relay = new DatagramSocket(0, InetAddress.getByAddress(LOOPBACK));
        open.add(relay);
        try {
            reply(out, REP_OK, relay.getLocalPort());
            control.setSoTimeout(0);

            UdpAssociation assoc = new UdpAssociation(relay, label);
            pool.execute(assoc::receiveLoop);
            while (in.read() != -1) {
                // control connection carries nothing after the request; wait for it to close
            }
        } finally {
            open.remove(relay);
            closeQuietly(relay);
        }
    }

    private final class UdpAssociation {
        private final DatagramSocket relay;
        private final AppSocket client; // query log label: who opened this association, null when hev named no app

        UdpAssociation(DatagramSocket relay, AppSocket client) {
            this.relay = relay;
            this.client = client;
        }

        void receiveLoop() {
            byte[] buf = new byte[UDP_HEADER + DNS_MAX];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                while (true) {
                    p.setLength(buf.length);
                    relay.receive(p);
                    // No pinning to the first sender: another local app that guessed the port could
                    // otherwise take the association over and cut hev off. Every answer goes back to
                    // the address its own query came from.
                    if (!isDnsQuery(buf, p.getLength())) {
                        udpDropped.incrementAndGet();
                        continue;
                    }
                    byte[] query = Arrays.copyOfRange(buf, UDP_HEADER, p.getLength());
                    SocketAddress to = p.getSocketAddress();
                    try {
                        dnsPool.execute(() -> answer(query, to)); // full queue: rejected and counted as dropped; the client retries
                    } catch (RejectedExecutionException e) {
                        udpDropped.incrementAndGet(); // bridge is stopping, or the DNS queue is full
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

        /** Runs on its own pool thread: resolving may block on the network. */
        private void answer(byte[] query, SocketAddress to) {
            try {
                byte[] response = dns.handle(client, query);
                if (response == null || response.length > DNS_MAX) {
                    udpDropped.incrementAndGet();
                    return;
                }
                byte[] packet = new byte[UDP_HEADER + response.length];
                packet[3] = ATYP_IPV4;
                System.arraycopy(dnsVirtual, 0, packet, 4, 4);
                packet[9] = 53;
                System.arraycopy(response, 0, packet, UDP_HEADER, response.length);
                relay.send(new DatagramPacket(packet, packet.length, to));
                dnsForwarded.incrementAndGet();
            } catch (IOException | RuntimeException e) {
                lastDnsError = e.getClass().getSimpleName() + ": " + e.getMessage();
                udpDropped.incrementAndGet();
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
