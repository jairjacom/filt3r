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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.bridge.filt3r.core.util.IpLiteral;

/**
 * A set of IP addresses to refuse, read from a text list with one address or CIDR range per line ('#'
 * starts a comment). IPv4 addresses are kept in one sorted int array, IPv6 addresses in a small set,
 * ranges in a short sorted array, so a list of ~100k addresses costs well under a megabyte.
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

    /** A range such as 10.0.0.0/4 is almost certainly a typo that would block the internet: ignored. */
    private static final int MIN_V4_BITS = 8;
    private static final int MIN_V6_BITS = 16;
    /** isBlocked walks these one by one on every answer, so there must be few. */
    private static final int MAX_V6_PREFIXES = 5000;

    private final int[] v4; // sorted, each address XOR 0x80000000 so that signed order equals unsigned order
    private final long[] ranges; // sorted, non-overlapping CIDR ranges: (first address key << 32) | last address key
    private final Set<ByteBuffer> v6;
    private final List<V6Prefix> v6Prefixes;

    private static final class V6Prefix {
        final byte[] address; // host bits are zero
        final int bits;

        V6Prefix(byte[] address, int bits) {
            this.address = address;
            this.bits = bits;
        }

        boolean contains(byte[] b) {
            int whole = bits / 8;
            for (int i = 0; i < whole; i++) {
                if (b[i] != address[i]) {
                    return false;
                }
            }
            int rest = bits % 8;
            if (rest == 0) {
                return true;
            }
            int mask = (0xFF << (8 - rest)) & 0xFF;
            return ((b[whole] ^ address[whole]) & mask) == 0;
        }
    }

    private IpBlockList(int[] v4, long[] ranges, Set<ByteBuffer> v6, List<V6Prefix> v6Prefixes) {
        this.v4 = v4;
        this.ranges = ranges;
        this.v6 = v6;
        this.v6Prefixes = v6Prefixes;
    }

    /** Entries: single addresses and ranges. */
    public int size() {
        return v4.length + ranges.length + v6.size() + v6Prefixes.size();
    }

    public boolean isBlocked(InetAddress a) {
        if (a == null) {
            return false;
        }
        byte[] b = a.getAddress();
        if (b.length == 4) {
            int key = key(b);
            return Arrays.binarySearch(v4, key) >= 0 || inRange(key);
        }
        if (!v6.isEmpty() && v6.contains(ByteBuffer.wrap(b))) {
            return true;
        }
        for (V6Prefix p : v6Prefixes) {
            if (p.contains(b)) {
                return true;
            }
        }
        return false;
    }

    private boolean inRange(int key) {
        if (ranges.length == 0) {
            return false;
        }
        // The last range whose first address is <= key; ranges do not overlap, so it is the only candidate.
        long probe = ((long) key << 32) | 0xFFFFFFFFL;
        int i = Arrays.binarySearch(ranges, probe);
        i = i >= 0 ? i : -i - 2;
        return i >= 0 && key <= (int) ranges[i];
    }

    private static int key(byte[] b) {
        return (((b[0] & 0xFF) << 24) | ((b[1] & 0xFF) << 16) | ((b[2] & 0xFF) << 8) | (b[3] & 0xFF)) ^ 0x80000000;
    }

    /** Collects entries while a list is read; {@link #build} sorts and merges them. */
    private static final class Builder {
        int[] v4 = new int[1024];
        int n4;
        long[] ranges = new long[16];
        int nRanges;
        final Set<ByteBuffer> v6 = new HashSet<>();
        final List<V6Prefix> v6Prefixes = new ArrayList<>();

        int count() {
            return n4 + nRanges + v6.size() + v6Prefixes.size();
        }

        /** One list token: an address, or an address with /bits. Anything else is skipped. */
        void add(String token) {
            int slash = token.indexOf('/');
            InetAddress a = IpLiteral.parse(slash < 0 ? token : token.substring(0, slash));
            if (a == null) {
                return;
            }
            byte[] b = a.getAddress();
            int bits = b.length * 8;
            if (slash >= 0) {
                String digits = token.substring(slash + 1);
                if (digits.isEmpty() || digits.length() > 3) {
                    return;
                }
                try {
                    bits = Integer.parseInt(digits);
                } catch (NumberFormatException e) {
                    return;
                }
            }
            if (b.length == 4) {
                if (bits == 32) {
                    addV4(key(b));
                } else if (bits >= MIN_V4_BITS && bits < 32) {
                    int mask = -1 << (32 - bits);
                    int first = (key(b) ^ 0x80000000) & mask;
                    addRange(first ^ 0x80000000, (first | ~mask) ^ 0x80000000);
                }
            } else if (bits == 128) {
                v6.add(ByteBuffer.wrap(b));
            } else if (bits >= MIN_V6_BITS && bits < 128) {
                byte[] masked = new byte[16];
                for (int i = 0; i < 16; i++) {
                    int keep = Math.max(0, Math.min(8, bits - i * 8));
                    masked[i] = (byte) (b[i] & (0xFF << (8 - keep)));
                }
                v6Prefixes.add(new V6Prefix(masked, bits));
            }
        }

        void addV4(int key) {
            if (n4 == v4.length) {
                v4 = Arrays.copyOf(v4, n4 * 2);
            }
            v4[n4++] = key;
        }

        void addRange(long firstKey, long lastKey) {
            if (nRanges == ranges.length) {
                ranges = Arrays.copyOf(ranges, nRanges * 2);
            }
            ranges[nRanges++] = (firstKey << 32) | (lastKey & 0xFFFFFFFFL);
        }

        void addAll(IpBlockList other) {
            for (int k : other.v4) {
                addV4(k);
            }
            for (long r : other.ranges) {
                addRange(r >> 32, (int) r);
            }
            v6.addAll(other.v6);
            v6Prefixes.addAll(other.v6Prefixes);
        }

        IpBlockList build() {
            int[] exact = Arrays.copyOf(v4, n4);
            Arrays.sort(exact);
            long[] sorted = Arrays.copyOf(ranges, nRanges);
            Arrays.sort(sorted);
            long[] merged = new long[sorted.length];
            int m = 0;
            for (long r : sorted) {
                if (m > 0 && (int) (r >> 32) <= (int) merged[m - 1]) { // starts inside the previous range
                    if ((int) r > (int) merged[m - 1]) {
                        merged[m - 1] = (merged[m - 1] & 0xFFFFFFFF00000000L) | ((int) r & 0xFFFFFFFFL);
                    }
                } else {
                    merged[m++] = r;
                }
            }
            return new IpBlockList(exact, Arrays.copyOf(merged, m), v6, v6Prefixes);
        }
    }

    /** This list and {@code other} together. */
    public IpBlockList plus(IpBlockList other) {
        Builder b = new Builder();
        b.addAll(this);
        b.addAll(other);
        return b.build();
    }

    /**
     * Reads a list: one address or CIDR range (203.0.113.0/24) per line, '#' starts a comment. Lines
     * that are not an address are skipped; a text with no address at all (an HTML error page, say) is
     * an error so that it can never replace a good list.
     */
    public static IpBlockList parse(InputStream in) throws IOException {
        InputStream src = new BufferedInputStream(new CappedInputStream(in, MAX_BYTES), 8192);
        Builder builder = new Builder();
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
            String token = tooLong ? null : firstToken(line, len);
            len = 0;
            tooLong = false;
            if (token == null) {
                continue;
            }
            builder.add(token);
            if (builder.count() > MAX_ENTRIES || builder.v6Prefixes.size() > MAX_V6_PREFIXES) {
                throw new IOException("IP list has too many entries");
            }
        } while (c != -1);

        if (builder.count() == 0) {
            throw new IOException(sawText ? "not an IP list" : "IP list is empty");
        }
        return builder.build();
    }

    /** The first token of the line, or null for a blank line or a comment. */
    private static String firstToken(byte[] line, int len) {
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
        return new String(line, start, end - start, java.nio.charset.StandardCharsets.US_ASCII);
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
