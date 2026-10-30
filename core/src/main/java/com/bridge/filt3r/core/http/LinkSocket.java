package com.bridge.filt3r.core.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;

import com.bridge.filt3r.core.socks.Link;

/** Presents a {@link Link} as a connected Socket, so TLS can run over a connection carried through a node. */
final class LinkSocket extends Socket {

    private final Link link;
    private final InetAddress address;
    private final int port;
    private boolean closed;
    private int timeoutMs;

    LinkSocket(Link link, InetAddress address, int port) {
        this.link = link;
        this.address = address;
        this.port = port;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return link.in();
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return link.out();
    }

    @Override
    public synchronized void setSoTimeout(int timeout) throws java.net.SocketException {
        try {
            link.setReadTimeout(timeout);
        } catch (IOException e) {
            throw new java.net.SocketException(e.getMessage());
        }
        timeoutMs = timeout;
    }

    @Override
    public synchronized int getSoTimeout() {
        return timeoutMs;
    }

    @Override
    public void shutdownOutput() throws IOException {
        link.shutdownOutput();
    }

    @Override
    public synchronized void close() throws IOException {
        closed = true;
        link.close();
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public InetAddress getInetAddress() {
        return address;
    }

    @Override
    public int getPort() {
        return port;
    }

    @Override
    public void setTcpNoDelay(boolean on) {
        // the link's own socket already has it set
    }

    @Override
    public void setKeepAlive(boolean on) {
        // not meaningful for a carried connection
    }
}
