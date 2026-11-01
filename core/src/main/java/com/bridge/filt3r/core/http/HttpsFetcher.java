package com.bridge.filt3r.core.http;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

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
 * Only https:// URLs are accepted.
 */
public final class HttpsFetcher {

    private static final int DIAL_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 60_000;
    private static final int MAX_TEXT_BODY = 8 * 1024 * 1024;
    private static final int MAX_HEADER = 16 * 1024;
    private static final int MAX_REDIRECTS = 3;
    /** One request (connect, headers and body, with its redirect hops counted separately) may take this long in all. */
    private static final long TOTAL_MS = 5 * 60_000L;
    private static final ScheduledThreadPoolExecutor WATCHDOG = new ScheduledThreadPoolExecutor(1, r -> {
        Thread t = new Thread(r, "fetch-watchdog");
        t.setDaemon(true);
        return t;
    });

    static {
        WATCHDOG.setRemoveOnCancelPolicy(true);
    }

    private final Dialer dialer;
    private final NameResolver resolver;
    private final Set<Closeable> active = ConcurrentHashMap.newKeySet();
    private volatile boolean cancelled;

    public HttpsFetcher(Dialer dialer, NameResolver resolver) {
        this.dialer = dialer;
        this.resolver = resolver;
    }

    /** Closes every connection of this fetcher that is open now and refuses new ones; a blocked read ends with an IOException. */
    public void cancel() {
        cancelled = true;
        for (Closeable c : active) {
            closeQuietly(c);
        }
    }

    private static void closeQuietly(Closeable c) {
        try {
            c.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    /** Downloads a small text document (at most 8 MB) as UTF-8. @throws IOException on any failure or a non-200 answer */
    public String get(String url) throws IOException {
        try (InputStream in = stream(url)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) {
                b.write(buf, 0, n);
                if (b.size() > MAX_TEXT_BODY) {
                    throw new IOException("response too large");
                }
            }
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Opens a download and returns the body as a stream, for large files. Redirects are followed.
     * Closing the stream closes the connection.
     */
    public InputStream stream(String url) throws IOException {
        String target = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URI uri = parse(target);
            Opened o = open(uri);
            if (o.status == 200) {
                return o.body;
            }
            o.body.close();
            if (o.status >= 300 && o.status < 400 && o.location != null) {
                target = uri.resolve(o.location).toString();
                continue;
            }
            throw new IOException("HTTP " + o.status);
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

    private static final class Opened {
        int status;
        String location;
        InputStream body; // closing it closes the connection
    }

    private Opened open(URI uri) throws IOException {
        if (cancelled) {
            throw new IOException("cancelled");
        }
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
            LinkSocket plain = new LinkSocket(link, a, port);
            // From here on a slow or stalled server is cut off at the deadline, and cancel() reaches the connection.
            active.add(plain);
            ScheduledFuture<?> watchdog = WATCHDOG.schedule(() -> closeQuietly(plain), TOTAL_MS, TimeUnit.MILLISECONDS);
            Runnable release = () -> {
                active.remove(plain);
                watchdog.cancel(false);
            };
            try {
                if (cancelled) {
                    throw new IOException("cancelled");
                }
                plain.setSoTimeout(READ_TIMEOUT_MS);
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(plain, host, port, true);
                SSLParameters p = tls.getSSLParameters();
                p.setEndpointIdentificationAlgorithm("HTTPS"); // verify the certificate against the host
                tls.setSSLParameters(p);
                tls.startHandshake();
                return request(tls, uri, release);
            } catch (IOException e) {
                last = e;
                release.run();
                closeQuietly(plain);
                if (cancelled) {
                    break;
                }
            }
        }
        throw last != null ? last : new IOException("no address to connect to");
    }

    private static Opened request(SSLSocket tls, URI uri, Runnable release) throws IOException {
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
        String[] lines = readHeader(in).split("\r\n");
        String[] status = lines[0].split(" ", 3);
        if (status.length < 2 || !status[0].startsWith("HTTP/")) {
            throw new IOException("bad HTTP response");
        }
        Opened o = new Opened();
        try {
            o.status = Integer.parseInt(status[1]);
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
                o.location = value;
            } else if (name.equals("content-encoding") && !value.equalsIgnoreCase("identity")) {
                throw new IOException("unsupported Content-Encoding: " + value);
            }
        }
        InputStream body = chunked ? new ChunkedInputStream(in) : length >= 0 ? new LengthInputStream(in, length) : in;
        o.body = new FilterInputStream(body) {
            @Override
            public void close() throws IOException {
                release.run();
                tls.close(); // also closes the carried connection
            }
        };
        return o;
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

    /** A body with a Content-Length: ends after that many bytes and fails if the connection ends early. */
    private static final class LengthInputStream extends InputStream {
        private final InputStream in;
        private long left;

        LengthInputStream(InputStream in, long length) {
            this.in = in;
            this.left = length;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) {
                return -1;
            }
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n < 0) {
                throw new EOFException("connection closed before the full body");
            }
            left -= n;
            return n;
        }
    }

    /** A chunked body: decodes the chunk framing and ends at the zero-length chunk (trailers are ignored). */
    private static final class ChunkedInputStream extends InputStream {
        private final InputStream in;
        private long left; // bytes left in the current chunk
        private boolean done;

        ChunkedInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (done) {
                return -1;
            }
            if (left == 0) {
                String line = readLine(in);
                int semi = line.indexOf(';');
                try {
                    left = Long.parseLong((semi >= 0 ? line.substring(0, semi) : line).trim(), 16);
                } catch (NumberFormatException e) {
                    throw new IOException("bad chunk size");
                }
                if (left == 0) {
                    done = true;
                    return -1;
                }
            }
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n < 0) {
                throw new EOFException("connection closed inside a chunk");
            }
            left -= n;
            if (left == 0) {
                readLine(in); // the CRLF after the chunk
            }
            return n;
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
