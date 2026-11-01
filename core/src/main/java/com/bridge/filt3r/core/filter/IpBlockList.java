package com.bridge.filt3r.core.filter;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import com.bridge.filt3r.core.util.IpLiteral;

/**
 * A set of IP addresses to refuse, read from a text list with one address per line ('#' starts a
 * comment). IPv4 addresses are kept in one sorted int array, IPv6 addresses in a small set, so a
 * list of ~100k addresses costs well under a megabyte.
 */
public final class IpBlockList {

    /** Hagezi's Threat Intelligence Feeds IP list: addresses of known malware, phishing and botnet servers. */
    public static final String DEFAULT_URL = "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/ips/tif.txt";

    /** Opens a URL and returns its decoded body. */
    public interface Opener {
        InputStream open(String url) throws IOException;
    }

    /** A hostile or broken server must not fill the storage or the heap. */
    private static final long MAX_BYTES = 8L << 20;
    private static final int MAX_ENTRIES = 1_000_000;
    /** The longest valid literal is 45 characters; anything longer is not an address. */
    private static final int MAX_LINE = 64;

    private final int[] v4; // sorted, each address XOR 0x80000000 so that signed order equals unsigned order
    private final Set<ByteBuffer> v6;

    private IpBlockList(int[] v4, Set<ByteBuffer> v6) {
        this.v4 = v4;
        this.v6 = v6;
    }

    public int size() {
        return v4.length + v6.size();
    }

    public boolean isBlocked(InetAddress a) {
        if (a == null) {
            return false;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            return Arrays.binarySearch(v4, key(b)) >= 0;
        }
        return !v6.isEmpty() && v6.contains(ByteBuffer.wrap(b));
    }

    private static int key(byte[] b) {
        return (((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF)) ^ 0x80000000;
    }

    /**
     * Reads a list. Lines that are not an IP address are skipped; a text with no address at all (an
     * HTML error page, say) is an error so that it can never replace a good list.
     */
    public static IpBlockList parse(InputStream in) throws IOException {
        InputStream src = new BufferedInputStream(new CappedInputStream(in, MAX_BYTES), 8192);
        int[] v4 = new int[1024];
        int n4 = 0;
        Set<ByteBuffer> v6 = new HashSet<>();
        boolean sawText = false;
        byte[] line = new byte[MAX_LINE];
        int len = 0;
        boolean tooLong = false;
        int c;
        do {
            c = src.read();
            if (c != -1 && c != '\n') {
                if (len < MAX_LINE) {
                    line[len++] = (byte) c;
                } else {
                    tooLong = true;
                }
                continue;
            }
            if (len > 0 || tooLong) {
                sawText = true;
            }
            InetAddress a = tooLong ? null : parseLine(line, len);
            len = 0;
            tooLong = false;
            if (a == null) {
                continue;
            }
            byte[] b = a.getAddress();
            if (b.length == 4) {
                if (n4 == v4.length) {
                    v4 = Arrays.copyOf(v4, n4 * 2);
                }
                v4[n4++] = key(b);
            } else {
                v6.add(ByteBuffer.wrap(b));
            }
            if (n4 + v6.size() > MAX_ENTRIES) {
                throw new IOException("IP list has too many entries");
            }
        } while (c != -1);

        if (n4 == 0 && v6.isEmpty()) {
            throw new IOException(sawText ? "not an IP list" : "IP list is empty");
        }
        v4 = Arrays.copyOf(v4, n4);
        Arrays.sort(v4);
        return new IpBlockList(v4, v6);
    }

    /** The first token of the line when it is an IP literal, else null (comments, blanks, junk). */
    private static InetAddress parseLine(byte[] line, int len) {
        int start = 0;
        while (start < len && (line[start] == ' ' || line[start] == '\t' || line[start] == '\r')) {
            start++;
        }
        if (start == len || line[start] == '#') {
            return null;
        }
        int end = start;
        while (end < len && line[end] != ' ' && line[end] != '\t' && line[end] != '\r') {
            end++;
        }
        return IpLiteral.parse(new String(line, start, end - start, java.nio.charset.StandardCharsets.US_ASCII));
    }

    /**
     * Returns the list from {@code cacheFile} when that is younger than {@code maxAgeMillis};
     * otherwise downloads {@code url}, checks that it parses, and only then replaces the cache. A
     * failed download falls back to the older cached copy; with no cache the failure is thrown.
     */
    public static IpBlockList refresh(File cacheFile, String url, Opener opener, long maxAgeMillis) throws IOException {
        if (cacheFile.isFile() && System.currentTimeMillis() - cacheFile.lastModified() < maxAgeMillis) {
            try {
                return load(cacheFile);
            } catch (IOException e) {
                // damaged cache: download again
            }
        }
        File tmp = new File(cacheFile.getPath() + ".tmp");
        try {
            try (InputStream in = new CappedInputStream(opener.open(url), MAX_BYTES);
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) {
                    out.write(buf, 0, r);
                }
            }
            IpBlockList list = load(tmp);
            Files.move(tmp.toPath(), cacheFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return list;
        } catch (IOException | RuntimeException e) {
            if (cacheFile.isFile()) {
                return load(cacheFile);
            }
            throw e instanceof IOException ? (IOException) e : new IOException(e);
        } finally {
            tmp.delete();
        }
    }

    static IpBlockList load(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return parse(in);
        }
    }
}
