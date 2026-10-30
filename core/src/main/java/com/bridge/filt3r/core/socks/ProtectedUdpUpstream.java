package com.bridge.filt3r.core.socks;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.Arrays;

import com.bridge.filt3r.core.filter.DNSResolver;

/**
 * Sends each query as one UDP datagram from a protected socket (outside the VPN) to a fixed
 * resolver. Interim upstream until the DNSCrypt client replaces it (Step 4): the resolver sees
 * the real IP and the query is not encrypted.
 */
public final class ProtectedUdpUpstream implements DNSResolver.Upstream {

    private static final int TIMEOUT_MS = 5_000;
    private static final int MAX_RESPONSE = 4096;

    private final SocketProtector protector;
    private final InetSocketAddress resolver;

    public ProtectedUdpUpstream(SocketProtector protector, InetSocketAddress resolver) {
        this.protector = protector;
        this.resolver = resolver;
    }

    @Override
    public byte[] query(byte[] request) throws IOException {
        try (DatagramSocket socket = new DatagramSocket()) {
            if (!protector.protect(socket)) {
                throw new IOException("protect() failed");
            }
            socket.setSoTimeout(TIMEOUT_MS);
            socket.connect(resolver);
            socket.send(new DatagramPacket(request, request.length));
            byte[] buf = new byte[MAX_RESPONSE];
            DatagramPacket p = new DatagramPacket(buf, buf.length);
            try {
                socket.receive(p);
            } catch (SocketTimeoutException e) {
                throw new IOException("DNS upstream timeout", e);
            }
            return Arrays.copyOf(buf, p.getLength());
        }
    }
}
