package com.bridge.filt3r.core.dnscrypt;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.bridge.filt3r.core.dns.DnsWire;
import com.bridge.filt3r.core.socks.Dialer;

/** Tests DNSCrypt resolvers with one real query each, sent through a {@link Dialer} (the node). */
public final class ResolverTester {

    private static final String TEST_NAME = "example.com";

    /** A resolver that answered, and how long the certificate fetch plus one query took. */
    public static final class Result {
        public final ResolverList.Entry entry;
        public final long millis;

        Result(ResolverList.Entry entry, long millis) {
            this.entry = entry;
            this.millis = millis;
        }
    }

    private ResolverTester() {
    }

    /** @return milliseconds; @throws IOException if the resolver is unreachable or not usable (cipher, protocol, signature) */
    public static long test(DnsCryptStamp stamp, Dialer dialer) throws IOException {
        long start = System.nanoTime();
        byte[] answer = new DnsCryptUpstream(stamp, dialer).query(DnsWire.query(TEST_NAME, DnsWire.TYPE_A));
        DnsWire.answers(answer, DnsWire.TYPE_A); // throws on a DNS error
        return (System.nanoTime() - start) / 1_000_000;
    }

    /** Tests all entries in parallel. @return the working ones, fastest first */
    public static List<Result> testAll(List<ResolverList.Entry> entries, Dialer dialer, int threads, long timeoutSeconds) {
        List<Result> out = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Math.min(threads, entries.size())));
        for (ResolverList.Entry e : entries) {
            pool.execute(() -> {
                try {
                    out.add(new Result(e, test(e.stamp, dialer)));
                } catch (IOException | RuntimeException ex) {
                    // dead or unusable: dropped
                }
            });
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException ex) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        List<Result> sorted;
        synchronized (out) {
            sorted = new ArrayList<>(out);
        }
        sorted.sort((a, b) -> Long.compare(a.millis, b.millis));
        return sorted;
    }
}
