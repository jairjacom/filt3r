package com.bridge.filt3r.core.http;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import com.bridge.filt3r.core.dns.NameResolver;
import com.bridge.filt3r.core.socks.Dialer;
import com.bridge.filt3r.core.socks.Link;

/**
 * A small HTTPS GET client for subscription and filter-list downloads. The name is resolved by a
 * {@link NameResolver} and the connection opened by a {@link Dialer}, so the same code can run
 * directly (bootstrap) or through the node. The certificate is checked against the URL's host name.
 */
public final class HttpsFetcher {

    private static final int DIAL_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final int MAX_BODY = 8 * 1024 * 1024;
    private static final int MAX_HEADER = 16 * 1024;
    private static final int MAX_REDIRECTS = 3;

    private final Dialer dialer;
    private final NameResolver resolver;

    public HttpsFetcher(Dialer dialer, NameResolver resolver) {
        this.dialer = dialer;
        this.resolver = resolver;
    }

    /** @return the response body as UTF-8 text; @throws IOException on any failure or a non-200 answer */
    public String get(String url) throws IOException {
        String target = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URI uri = parse(target);
            Response r = request(uri);
            if (r.status == 200) {
                return new String(r.body, StandardCharsets.UTF_8);
            }
            if (r.status >= 300 && r.status < 400 && r.location != null) {
                target = uri.resolve(r.location).toString();
                continue;
            }
            throw new IOException("HTTP " + r.status);
        }
        throw new IOException("too many redirects");
    }

    private static URI parse(String url) throws IOException {
        try {
            URI uri = new URI(url.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new IOException("only https:// URLs are supported");
            }
            return uri;
        } catch (URISyntaxException e) {
            throw new IOException("bad URL");
        }
    }

    private static final class Response {
        int status;
        String location;
        byte[] body;
    }

    private Response request(URI uri) throws IOException {
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 443;
        List<InetAddress> addresses = resolver.resolve(host);
        IOException last = null;
        for (InetAddress a : addresses) {
            Link link;
            try {
                link = dialer.dial(a, port, DIAL_TIMEOUT_MS);
            } catch (IOException e) {
                last = e;
                continue;
            }
            try (LinkSocket plain = new LinkSocket(link, a, port)) {
                plain.setSoTimeout(READ_TIMEOUT_MS);
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(plain, host, port, true);
                SSLParameters p = tls.getSSLParameters();
                p.setEndpointIdentificationAlgorithm("HTTPS"); // verify the certificate against the host
                tls.setSSLParameters(p);
                tls.startHandshake();
                return exchange(tls, uri);
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("no address to connect to");
    }

    private static Response exchange(SSLSocket tls, URI uri) throws IOException {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }
        String req = "GET " + path + " HTTP/1.1\r\nHost: " + uri.getHost()
                + "\r\nUser-Agent: filt3r\r\nAccept: */*\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n";
        OutputStream out = tls.getOutputStream();
        out.write(req.getBytes(StandardCharsets.US_ASCII));
        out.flush();

        InputStream in = tls.getInputStream();
        String head = readHeader(in);
        String[] lines = head.split("\r\n");
        String[] status = lines[0].split(" ", 3);
        if (status.length < 2 || !status[0].startsWith("HTTP/")) {
            throw new IOException("bad HTTP response");
        }
        Response r = new Response();
        try {
            r.status = Integer.parseInt(status[1]);
        } catch (NumberFormatException e) {
            throw new IOException("bad HTTP status");
        }
        long length = -1;
        boolean chunked = false;
        for (int i = 1; i < lines.length; i++) {
            int c = lines[i].indexOf(':');
            if (c < 0) {
                continue;
            }
            String name = lines[i].substring(0, c).trim().toLowerCase(Locale.ROOT);
            String value = lines[i].substring(c + 1).trim();
            if (name.equals("content-length")) {
                try {
                    length = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    throw new IOException("bad Content-Length");
                }
            } else if (name.equals("transfer-encoding") && value.toLowerCase(Locale.ROOT).contains("chunked")) {
                chunked = true;
            } else if (name.equals("location")) {
                r.location = value;
            }
        }
        if (r.status != 200) {
            return r; // body not needed
        }
        r.body = chunked ? readChunked(in) : readBody(in, length);
        return r;
    }

    /** Reads up to and including the blank line; returns the text before it. */
    private static String readHeader(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0; // matches \r\n\r\n
        while (state < 4) {
            int c = in.read();
            if (c < 0) {
                throw new EOFException("connection closed in HTTP header");
            }
            b.write(c);
            if (b.size() > MAX_HEADER) {
                throw new IOException("HTTP header too large");
            }
            state = (c == '\r' && (state == 0 || state == 2)) ? state + 1
                    : (c == '\n' && (state == 1 || state == 3)) ? state + 1
                    : (c == '\r') ? 1 : 0;
        }
        return new String(b.toByteArray(), 0, b.size() - 4, StandardCharsets.ISO_8859_1);
    }

    private static byte[] readBody(InputStream in, long length) throws IOException {
        if (length > MAX_BODY) {
            throw new IOException("response too large");
        }
        ByteArrayOutputStream b = new ByteArrayOutputStream(length > 0 ? (int) length : 8192);
        byte[] buf = new byte[8192];
        while (length < 0 || b.size() < length) {
            int want = length < 0 ? buf.length : (int) Math.min(buf.length, length - b.size());
            int n = in.read(buf, 0, want);
            if (n < 0) {
                if (length >= 0) {
                    throw new EOFException("connection closed before the full body");
                }
                break; // no length: the end of the connection is the end of the body
            }
            b.write(buf, 0, n);
            if (b.size() > MAX_BODY) {
                throw new IOException("response too large");
            }
        }
        return b.toByteArray();
    }

    private static byte[] readChunked(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        while (true) {
            String line = readLine(in);
            int semi = line.indexOf(';');
            int size;
            try {
                size = Integer.parseInt((semi >= 0 ? line.substring(0, semi) : line).trim(), 16);
            } catch (NumberFormatException e) {
                throw new IOException("bad chunk size");
            }
            if (size == 0) {
                return b.toByteArray(); // trailers are ignored
            }
            if (b.size() + size > MAX_BODY) {
                throw new IOException("response too large");
            }
            byte[] chunk = new byte[size];
            int got = 0;
            while (got < size) {
                int n = in.read(chunk, got, size - got);
                if (n < 0) {
                    throw new EOFException("connection closed inside a chunk");
                }
                got += n;
            }
            b.write(chunk, 0, size);
            readLine(in); // the CRLF after the chunk
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder s = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c < 0) {
                throw new EOFException("connection closed in chunked body");
            }
            if (c == '\n') {
                return s.toString().trim();
            }
            s.append((char) c);
            if (s.length() > 64) {
                throw new IOException("bad chunk header");
            }
        }
    }
}
