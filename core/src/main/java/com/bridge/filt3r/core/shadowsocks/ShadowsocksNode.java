package com.bridge.filt3r.core.shadowsocks;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/** One Shadowsocks server, parsed from an {@code ss://} URL. Never log or print this object's fields. */
public final class ShadowsocksNode {

    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    public final AeadSuite suite;
    public final String password;
    public final String host;
    public final int port;
    public final String name;

    private ShadowsocksNode(AeadSuite suite, String password, String host, int port, String name) {
        this.suite = suite;
        this.password = password;
        this.host = host;
        this.port = port;
        this.name = name;
    }

    /**
     * Accepts SIP002 ({@code ss://base64(method:password)@host:port#name} or with a percent-encoded
     * plain userinfo) and the legacy form {@code ss://base64(method:password@host:port)#name}.
     * Plugins are not supported.
     *
     * @throws IllegalArgumentException if the URL is malformed or uses an unsupported method
     */
    public static ShadowsocksNode parse(String uri) {
        String s = uri.trim();
        if (!s.regionMatches(true, 0, "ss://", 0, 5)) {
            throw new IllegalArgumentException("not an ss:// URL");
        }
        s = s.substring(5);
        String name = "";
        int hash = s.indexOf('#');
        if (hash >= 0) {
            name = decodeUrl(s.substring(hash + 1));
            s = s.substring(0, hash);
        }
        if (s.contains("?")) {
            throw new IllegalArgumentException("plugins are not supported");
        }
        s = s.replaceAll("/+$", "");

        String userinfo;
        String hostPort;
        int at = s.lastIndexOf('@');
        if (at >= 0) {
            userinfo = s.substring(0, at);
            hostPort = s.substring(at + 1);
            if (!userinfo.contains(":")) {
                userinfo = decodeBase64(userinfo);
            } else {
                userinfo = decodeUrl(userinfo);
            }
        } else {
            String whole = decodeBase64(s);
            int at2 = whole.lastIndexOf('@');
            if (at2 < 0) {
                throw new IllegalArgumentException("missing @host:port");
            }
            userinfo = whole.substring(0, at2);
            hostPort = whole.substring(at2 + 1);
        }

        int colon = userinfo.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("missing method:password");
        }
        AeadSuite suite = AeadSuite.fromMethod(userinfo.substring(0, colon));
        String password = userinfo.substring(colon + 1);

        String host;
        String portText;
        if (hostPort.startsWith("[")) {
            int end = hostPort.indexOf(']');
            if (end < 0 || hostPort.length() < end + 3 || hostPort.charAt(end + 1) != ':') {
                throw new IllegalArgumentException("bad host:port");
            }
            host = hostPort.substring(1, end);
            portText = hostPort.substring(end + 2);
        } else {
            int c = hostPort.lastIndexOf(':');
            if (c <= 0) {
                throw new IllegalArgumentException("bad host:port");
            }
            host = hostPort.substring(0, c);
            portText = hostPort.substring(c + 1);
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("bad port");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("bad port");
        }
        return new ShadowsocksNode(suite, password, host, port, name);
    }

    /** @throws IllegalArgumentException on an unsupported method, empty password, empty host or bad port */
    public static ShadowsocksNode of(String method, String password, String host, int port, String name) {
        if (password == null || password.isEmpty() || host == null || host.isEmpty()) {
            throw new IllegalArgumentException("missing password or host");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("bad port");
        }
        return new ShadowsocksNode(AeadSuite.fromMethod(method), password, host, port, name == null ? "" : name);
    }

    /** A SIP002 URL that {@link #parse} reads back. Contains the password: store it only in private storage. */
    public String toUri() {
        String userinfo = Base64.getUrlEncoder().withoutPadding().encodeToString(
                (suite.methodName + ":" + password).getBytes(StandardCharsets.UTF_8));
        String hostText = host.contains(":") ? "[" + host + "]" : host;
        return "ss://" + userinfo + "@" + hostText + ":" + port + "#"
                + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * The server address. Only IP literals are accepted: a host name would be looked up outside the
     * tunnel and reveal the node to the network. Name handling arrives with the bootstrap step.
     */
    public InetAddress address() throws IOException {
        if (!IPV4.matcher(host).matches() && !host.contains(":")) {
            throw new IOException("node host must be an IP address");
        }
        return InetAddress.getByName(host); // literal: no DNS lookup
    }

    private static String decodeBase64(String text) {
        try {
            String t = text.replace('-', '+').replace('_', '/').replaceAll("=+$", "");
            return new String(Base64.getDecoder().decode(t), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("bad base64");
        }
    }

    private static String decodeUrl(String text) {
        return URLDecoder.decode(text.replace("+", "%2B"), StandardCharsets.UTF_8);
    }
}
