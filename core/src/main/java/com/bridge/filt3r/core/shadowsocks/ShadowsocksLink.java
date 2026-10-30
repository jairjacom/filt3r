package com.bridge.filt3r.core.shadowsocks;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.security.SecureRandom;

import com.bridge.filt3r.core.socks.Link;

/**
 * Carries one connection through a Shadowsocks AEAD server. Wire format: each direction starts with
 * a random salt, then chunks of [encrypted 2-byte length + tag][encrypted payload + tag]. The first
 * client payload starts with the target address, which is sent together with the first data (or
 * when the application first reads or half-closes).
 */
final class ShadowsocksLink implements Link {

    private static final int MAX_CHUNK = 0x3FFF;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Link raw;
    private final AeadSuite suite;
    private final byte[] masterKey;
    private final Object writeLock = new Object();

    private byte[] pendingHeader;       // guarded by writeLock; null once sent
    private AeadSuite.Session writer;   // guarded by writeLock
    private AeadSuite.Session reader;   // used only by the reading thread
    private byte[] readBuf = new byte[0];
    private int readPos;

    private final InputStream in = new InputStream() {
        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            sendPending(); // a server-first protocol never writes; the request must still go out
            while (readPos >= readBuf.length) {
                if (!nextChunk()) {
                    return -1;
                }
            }
            int n = Math.min(len, readBuf.length - readPos);
            System.arraycopy(readBuf, readPos, b, off, n);
            readPos += n;
            return n;
        }
    };

    private final OutputStream out = new OutputStream() {
        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            send(b, off, len);
        }
    };

    ShadowsocksLink(Link raw, AeadSuite suite, byte[] masterKey, InetAddress target, int port) {
        this.raw = raw;
        this.suite = suite;
        this.masterKey = masterKey;
        this.pendingHeader = addressHeader(target, port);
    }

    private static byte[] addressHeader(InetAddress target, int port) {
        byte[] addr = target.getAddress();
        byte[] h = new byte[1 + addr.length + 2];
        h[0] = (byte) (target instanceof Inet6Address ? 4 : 1);
        System.arraycopy(addr, 0, h, 1, addr.length);
        h[h.length - 2] = (byte) (port >> 8);
        h[h.length - 1] = (byte) port;
        return h;
    }

    @Override
    public InputStream in() {
        return in;
    }

    @Override
    public OutputStream out() {
        return out;
    }

    @Override
    public void setReadTimeout(int ms) throws IOException {
        raw.setReadTimeout(ms);
    }

    @Override
    public void shutdownOutput() throws IOException {
        sendPending();
        raw.shutdownOutput();
    }

    @Override
    public void close() throws IOException {
        raw.close();
    }

    private void sendPending() throws IOException {
        synchronized (writeLock) {
            if (pendingHeader != null) {
                send(new byte[0], 0, 0);
            }
        }
    }

    /** Encrypts and writes data, with the salt and address header in front of the first call. */
    private void send(byte[] data, int off, int len) throws IOException {
        synchronized (writeLock) {
            java.io.ByteArrayOutputStream frame = new java.io.ByteArrayOutputStream(len + 64);
            byte[] payload;
            if (pendingHeader != null) {
                byte[] salt = new byte[suite.keyLen];
                RANDOM.nextBytes(salt);
                writer = suite.session(masterKey, salt);
                frame.write(salt);
                payload = new byte[pendingHeader.length + len];
                System.arraycopy(pendingHeader, 0, payload, 0, pendingHeader.length);
                System.arraycopy(data, off, payload, pendingHeader.length, len);
                pendingHeader = null;
                off = 0;
                len = payload.length;
            } else {
                payload = data;
            }
            for (int pos = 0; pos < len; ) {
                int n = Math.min(MAX_CHUNK, len - pos);
                byte[] length = {(byte) (n >> 8), (byte) n};
                frame.write(writer.seal(length, 0, 2));
                frame.write(writer.seal(payload, off + pos, n));
                pos += n;
            }
            OutputStream o = raw.out();
            o.write(frame.toByteArray());
            o.flush();
        }
    }

    /** Reads and decrypts the next chunk into readBuf. @return false on a clean end of stream */
    private boolean nextChunk() throws IOException {
        InputStream r = raw.in();
        if (reader == null) {
            byte[] salt = new byte[suite.keyLen];
            if (!readFully(r, salt, true)) {
                return false;
            }
            reader = suite.session(masterKey, salt);
        }
        byte[] head = new byte[2 + AeadSuite.TAG_LEN];
        if (!readFully(r, head, true)) {
            return false;
        }
        byte[] lenBytes = reader.open(head, 0, head.length);
        int n = ((lenBytes[0] & 0xFF) << 8) | (lenBytes[1] & 0xFF);
        if (n > MAX_CHUNK) {
            throw new IOException("bad chunk length");
        }
        byte[] body = new byte[n + AeadSuite.TAG_LEN];
        readFully(r, body, false);
        readBuf = reader.open(body, 0, body.length);
        readPos = 0;
        return true;
    }

    /** @return false if the stream ended before the first byte and {@code eofOk} is set */
    private static boolean readFully(InputStream in, byte[] buf, boolean eofOk) throws IOException {
        int got = 0;
        while (got < buf.length) {
            int n = in.read(buf, got, buf.length - got);
            if (n < 0) {
                if (got == 0 && eofOk) {
                    return false;
                }
                throw new EOFException("truncated Shadowsocks stream");
            }
            got += n;
        }
        return true;
    }
}
