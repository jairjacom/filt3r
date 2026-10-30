package com.bridge.filt3r.core.socks;

import java.net.DatagramSocket;
import java.net.Socket;

/** Keeps a socket outside the VPN. The Android layer implements it with VpnService.protect(). */
public interface SocketProtector {

    boolean protect(Socket socket);

    boolean protect(DatagramSocket socket);
}
