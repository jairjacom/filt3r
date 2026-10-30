package com.bridge.filt3r.core.socks;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Connects straight to the destination over a protected socket (outside the VPN). */
public final class DirectDialer implements Dialer {

    private final SocketProtector protector;

    public DirectDialer(SocketProtector protector) {
        this.protector = protector;
    }

    @Override
    public Link dial(InetAddress address, int port, int timeoutMs) throws IOException {
        Socket socket = new Socket();
        try {
            socket.bind(null); // creates the fd; protect() needs it
            if (!protector.protect(socket)) {
                throw new IOException("protect failed");
            }
            socket.connect(new InetSocketAddress(address, port), timeoutMs);
            socket.setTcpNoDelay(true);
            return new SocketLink(socket);
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // nothing to do
            }
            throw e;
        }
    }
}
