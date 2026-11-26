package com.bridge.filt3r.core.socks;

import com.bridge.filt3r.core.util.IpLiteral;

import java.net.InetAddress;

/**
 * The app's own socket inside the tunnel, as hev names it after the bridge user name ("address#port").
 * Only the bridge creates these; {@link #toString()} is the text the query log shows, for example
 * "udp:10.0.2.15#40000".
 */
public final class AppSocket {

    public enum Proto { TCP, UDP }

    private final Proto proto;
    private final InetAddress ip;
    private final int port;
    private final String text;

    private AppSocket(Proto proto, InetAddress ip, int port, String text) {
        this.proto = proto;
        this.ip = ip;
        this.port = port;
        this.text = text;
    }

    /**
     * @param source the text after the bridge user name, "address#port"; the port is 1 to 65535
     * @return the socket, or null when the source is empty or malformed (hev did not name the app)
     */
    public static AppSocket parse(Proto proto, String source) {
        if (source == null) {
            return null;
        }
        int hash = source.lastIndexOf('#');
        if (hash <= 0 || hash == source.length() - 1) {
            return null;
        }
        InetAddress ip = IpLiteral.parse(source.substring(0, hash));
        if (ip == null) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(source.substring(hash + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (port < 1 || port > 65535) {
            return null;
        }
        return new AppSocket(proto, ip, port, proto.name().toLowerCase(java.util.Locale.ROOT) + ":" + source);
    }

    public Proto proto() {
        return proto;
    }

    public InetAddress ip() {
        return ip;
    }

    public int port() {
        return port;
    }

    @Override
    public String toString() {
        return text;
    }
}
