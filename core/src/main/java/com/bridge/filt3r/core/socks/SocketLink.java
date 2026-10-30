package com.bridge.filt3r.core.socks;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/** A {@link Link} over a plain socket. */
public final class SocketLink implements Link {

    private final Socket socket;

    public SocketLink(Socket socket) {
        this.socket = socket;
    }

    @Override
    public InputStream in() throws IOException {
        return socket.getInputStream();
    }

    @Override
    public OutputStream out() throws IOException {
        return socket.getOutputStream();
    }

    @Override
    public void shutdownOutput() throws IOException {
        socket.shutdownOutput();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
