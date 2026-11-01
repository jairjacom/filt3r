package com.bridge.filt3r.core.filter;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Passes at most {@code limit} bytes through, then fails: a download that is too large is an error, not a short list. */
final class CappedInputStream extends FilterInputStream {

    private final long limit;
    private long used;

    CappedInputStream(InputStream in, long limit) {
        super(in);
        this.limit = limit;
    }

    @Override
    public int read() throws IOException {
        int b = super.read();
        if (b >= 0) {
            count(1);
        }
        return b;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = super.read(b, off, len);
        if (n > 0) {
            count(n);
        }
        return n;
    }

    @Override
    public long skip(long n) throws IOException {
        long skipped = super.skip(n);
        if (skipped > 0) {
            count(skipped);
        }
        return skipped;
    }

    @Override
    public boolean markSupported() {
        return false;
    }

    private void count(long n) throws IOException {
        used += n;
        if (used > limit) {
            throw new IOException("list is larger than " + (limit >> 20) + " MB");
        }
    }
}
