package com.bridge.filt3r.core.dns;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

/**
 * Bootstrap lookups over DNS-over-HTTPS to a fixed IP, used before any node is up. The IP is in the
 * URL, so no name is resolved to reach it, and the certificate is checked against that IP.
 * This resolver sees the real IP and the names asked (a documented cost of bootstrapping).
 */
public final class DohBootstrapResolver implements NameResolver {

    private static final int TIMEOUT_MS = 8_000;
    private static final int MAX_RESPONSE = 4096;

    private final URL url;

    /** @param endpoint for example {@code https://1.1.1.1/dns-query} */
    public DohBootstrapResolver(String endpoint) throws IOException {
        this.url = new URL(endpoint);
    }

    @Override
    public List<InetAddress> resolve(String host) throws IOException {
        byte[] query = DnsWire.query(host, DnsWire.TYPE_A);
        HttpsURLConnection c = (HttpsURLConnection) url.openConnection();
        try {
            c.setRequestMethod("POST");
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/dns-message");
            c.setRequestProperty("Accept", "application/dns-message");
            try (OutputStream out = c.getOutputStream()) {
                out.write(query);
            }
            if (c.getResponseCode() != 200) {
                throw new IOException("DoH bootstrap HTTP " + c.getResponseCode());
            }
            byte[] response = readLimited(c.getInputStream());
            List<InetAddress> out = new ArrayList<>();
            for (byte[] a : DnsWire.answers(response, DnsWire.TYPE_A)) {
                out.add(InetAddress.getByAddress(a));
            }
            if (out.isEmpty()) {
                throw new IOException("no address for host");
            }
            return out;
        } finally {
            c.disconnect();
        }
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            b.write(buf, 0, n);
            if (b.size() > MAX_RESPONSE) {
                throw new IOException("DoH response too large");
            }
        }
        return b.toByteArray();
    }
}
