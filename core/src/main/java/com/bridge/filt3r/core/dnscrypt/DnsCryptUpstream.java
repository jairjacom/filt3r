package com.bridge.filt3r.core.dnscrypt;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.KeyAgreement;

import com.bridge.filt3r.core.filter.DNSResolver;
import com.bridge.filt3r.core.socks.Dialer;
import com.bridge.filt3r.core.socks.Link;

/**
 * Sends DNS queries to a DNSCrypt v2 resolver over TCP (es-version 2, XChaCha20-Poly1305), through
 * a {@link Dialer}. Give it the node's dialer and the resolver sees only the node's address.
 * The resolver certificate is fetched and checked against the stamp's provider key, and renewed
 * shortly before it expires. Each query uses its own TCP connection.
 */
public final class DnsCryptUpstream implements DNSResolver.Upstream {

    private static final int DIAL_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 6_000;
    private static final long RENEW_MARGIN_MS = 60_000;
    private static final int MIN_PADDED = 256;
    private static final int CERT_LEN = 124;
    private static final int ES_XCHACHA20 = 2;
    private static final byte[] CERT_MAGIC = {'D', 'N', 'S', 'C'};
    private static final byte[] RESOLVER_MAGIC = {'r', '6', 'f', 'n', 'v', 'W', 'j', '8'};
    private static final byte[] ED25519_PREFIX = hex("302a300506032b6570032100");
    private static final byte[] X25519_PREFIX = hex("302a300506032b656e032100");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DnsCryptStamp stamp;
    private final Dialer dialer;
    private Cert cert; // guarded by this

    public DnsCryptUpstream(DnsCryptStamp stamp, Dialer dialer) {
        this.stamp = stamp;
        this.dialer = dialer;
    }

    @Override
    public byte[] query(byte[] request) throws IOException {
        Cert c = currentCert();
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        byte[] packet = encrypt(c, nonce, request);
        // One connection per query. A reused connection was tried: through the test node it came back
        // EOF after about 2 s, so every second query cost 2.4 s instead of 0.4 s.
        Link link = open();
        try {
            writeFrame(link.out(), packet);
            return decrypt(c, nonce, readFrame(link.in()));
        } finally {
            closeQuietly(link);
        }
    }

    private static byte[] encrypt(Cert c, byte[] clientNonce, byte[] query) {
        int padded = Math.max(MIN_PADDED, (query.length + 1 + 63) / 64 * 64);
        byte[] plain = new byte[padded];
        System.arraycopy(query, 0, plain, 0, query.length);
        plain[query.length] = (byte) 0x80;
        byte[] box = XChaChaBox.seal(plain, fullNonce(clientNonce), c.sharedKey);
        byte[] out = new byte[8 + 32 + 12 + box.length];
        System.arraycopy(c.clientMagic, 0, out, 0, 8);
        System.arraycopy(c.clientPublic, 0, out, 8, 32);
        System.arraycopy(clientNonce, 0, out, 40, 12);
        System.arraycopy(box, 0, out, 52, box.length);
        return out;
    }

    private static byte[] decrypt(Cert c, byte[] clientNonce, byte[] response) throws IOException {
        if (response.length < 8 + 24 + XChaChaBox.TAG_LEN || !Arrays.equals(Arrays.copyOf(response, 8), RESOLVER_MAGIC)) {
            throw new IOException("not a DNSCrypt response");
        }
        byte[] nonce = Arrays.copyOfRange(response, 8, 32);
        if (!Arrays.equals(Arrays.copyOf(nonce, 12), clientNonce)) {
            throw new IOException("DNSCrypt response nonce mismatch");
        }
        byte[] plain = XChaChaBox.open(response, 32, response.length - 32, nonce, c.sharedKey);
        int end = plain.length;
        while (end > 0 && plain[end - 1] == 0) {
            end--;
        }
        if (end == 0 || plain[end - 1] != (byte) 0x80) {
            throw new IOException("bad DNSCrypt padding");
        }
        return Arrays.copyOf(plain, end - 1);
    }

    private static byte[] fullNonce(byte[] clientNonce) {
        return Arrays.copyOf(clientNonce, XChaChaBox.NONCE_LEN); // client half, then zeros
    }

    // ---- certificate ----

    private static final class Cert {
        final byte[] sharedKey;
        final byte[] clientPublic;
        final byte[] clientMagic;
        final long validUntilMs;

        Cert(byte[] sharedKey, byte[] clientPublic, byte[] clientMagic, long validUntilMs) {
            this.sharedKey = sharedKey;
            this.clientPublic = clientPublic;
            this.clientMagic = clientMagic;
            this.validUntilMs = validUntilMs;
        }
    }

    private synchronized Cert currentCert() throws IOException {
        long now = System.currentTimeMillis();
        if (cert == null || now > cert.validUntilMs - RENEW_MARGIN_MS) {
            try {
                cert = fetchCert();
            } catch (IOException e) {
                if (cert == null || now > cert.validUntilMs) {
                    throw e;
                }
            }
        }
        return cert;
    }

    private Cert fetchCert() throws IOException {
        byte[] answer;
        Link link = open();
        try {
            writeFrame(link.out(), txtQuery(stamp.providerName));
            answer = readFrame(link.in());
        } finally {
            closeQuietly(link);
        }
        long now = System.currentTimeMillis();
        byte[] best = null;
        long bestSerial = -1;
        for (byte[] txt : txtRecords(answer)) {
            if (txt.length != CERT_LEN || !Arrays.equals(Arrays.copyOf(txt, 4), CERT_MAGIC)
                    || u16(txt, 4) != ES_XCHACHA20 || !signatureOk(txt)) {
                continue;
            }
            long serial = u32(txt, 112);
            if (now < u32(txt, 116) * 1000 || now > u32(txt, 120) * 1000 || serial <= bestSerial) {
                continue;
            }
            best = txt;
            bestSerial = serial;
        }
        if (best == null) {
            throw new IOException("no valid XChaCha20 DNSCrypt certificate from the resolver");
        }
        return newCert(best);
    }

    private boolean signatureOk(byte[] cert) {
        try {
            byte[] x509 = Arrays.copyOf(ED25519_PREFIX, ED25519_PREFIX.length + 32);
            System.arraycopy(stamp.providerKey, 0, x509, ED25519_PREFIX.length, 32);
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(x509));
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(key);
            sig.update(cert, 72, CERT_LEN - 72);
            return sig.verify(Arrays.copyOfRange(cert, 8, 72));
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private static Cert newCert(byte[] cert) throws IOException {
        try {
            KeyPair mine = KeyPairGenerator.getInstance("X25519").generateKeyPair();
            // The platform's key classes differ (Android's is not an XECPublicKey), so use the
            // RFC 8410 encoding: a fixed 12-byte prefix, then the 32 raw key bytes.
            byte[] encoded = mine.getPublic().getEncoded();
            byte[] clientPublic = Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
            byte[] theirs = Arrays.copyOf(X25519_PREFIX, X25519_PREFIX.length + 32);
            System.arraycopy(cert, 72, theirs, X25519_PREFIX.length, 32);
            theirs[theirs.length - 1] &= 0x7F; // RFC 7748: the top bit of a u-coordinate is ignored
            PublicKey resolverKey = KeyFactory.getInstance("X25519").generatePublic(new X509EncodedKeySpec(theirs));
            KeyAgreement ka = KeyAgreement.getInstance("X25519");
            ka.init(mine.getPrivate());
            ka.doPhase(resolverKey, true);
            byte[] shared = ka.generateSecret();
            return new Cert(XChaChaBox.sharedKey(shared), clientPublic, Arrays.copyOfRange(cert, 104, 112),
                    u32(cert, 120) * 1000);
        } catch (GeneralSecurityException e) {
            throw new IOException("X25519 key exchange failed: " + e.getMessage(), e);
        }
    }

    // ---- plain DNS for the certificate (TXT query, TCP) ----

    private static byte[] txtQuery(String name) {
        ByteArrayOutputStream q = new ByteArrayOutputStream();
        byte[] id = new byte[2];
        RANDOM.nextBytes(id);
        q.write(id, 0, 2);
        q.write(0x01); // recursion desired
        q.write(0x00);
        q.write(0);
        q.write(1); // one question
        q.write(0);
        q.write(0);
        q.write(0);
        q.write(0);
        q.write(0);
        q.write(0);
        for (String label : name.split("\\.")) {
            byte[] l = label.getBytes(StandardCharsets.US_ASCII);
            q.write(l.length);
            q.write(l, 0, l.length);
        }
        q.write(0);
        q.write(0);
        q.write(16); // TXT
        q.write(0);
        q.write(1); // IN
        return q.toByteArray();
    }

    /** The character strings of every TXT record in the answer section. */
    static java.util.List<byte[]> txtRecords(byte[] m) throws IOException {
        java.util.List<byte[]> out = new java.util.ArrayList<>();
        try {
            if (m.length < 12 || (m[3] & 0x0F) != 0) {
                throw new IOException("certificate query failed (rcode " + (m.length < 12 ? -1 : m[3] & 0x0F) + ")");
            }
            int questions = u16(m, 4);
            int answers = u16(m, 6);
            int pos = 12;
            for (int i = 0; i < questions; i++) {
                pos = skipName(m, pos) + 4;
            }
            for (int i = 0; i < answers; i++) {
                pos = skipName(m, pos);
                int type = u16(m, pos);
                int rdlen = u16(m, pos + 8);
                pos += 10;
                int end = pos + rdlen;
                if (type == 16) {
                    for (int p = pos; p < end; p += 1 + (m[p] & 0xFF)) {
                        out.add(Arrays.copyOfRange(m, p + 1, p + 1 + (m[p] & 0xFF)));
                    }
                }
                pos = end;
            }
        } catch (IndexOutOfBoundsException e) {
            throw new IOException("malformed certificate response");
        }
        return out;
    }

    private static int skipName(byte[] m, int pos) {
        while (true) {
            int len = m[pos] & 0xFF;
            if (len == 0) {
                return pos + 1;
            }
            if ((len & 0xC0) == 0xC0) {
                return pos + 2;
            }
            pos += 1 + len;
        }
    }

    // ---- connections ----

    private Link open() throws IOException {
        Link link = dialer.dial(stamp.address, stamp.port, DIAL_TIMEOUT_MS);
        try {
            link.setReadTimeout(READ_TIMEOUT_MS);
        } catch (IOException e) {
            closeQuietly(link);
            throw e;
        }
        return link;
    }

    private static void writeFrame(OutputStream out, byte[] packet) throws IOException {
        byte[] framed = new byte[2 + packet.length];
        framed[0] = (byte) (packet.length >> 8);
        framed[1] = (byte) packet.length;
        System.arraycopy(packet, 0, framed, 2, packet.length);
        out.write(framed);
        out.flush();
    }

    private static byte[] readFrame(InputStream in) throws IOException {
        byte[] head = new byte[2];
        readFully(in, head);
        byte[] body = new byte[((head[0] & 0xFF) << 8) | (head[1] & 0xFF)];
        readFully(in, body);
        return body;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int got = 0;
        while (got < buf.length) {
            int n = in.read(buf, got, buf.length - got);
            if (n < 0) {
                throw new EOFException("DNS connection closed");
            }
            got += n;
        }
    }

    private static void closeQuietly(Link link) {
        try {
            link.close();
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    // ---- byte helpers ----

    private static int u16(byte[] b, int off) {
        return (b[off] & 0xFF) << 8 | (b[off + 1] & 0xFF);
    }

    /** Big endian, as DNSCrypt certificates store serial and times. */
    private static long u32(byte[] b, int off) {
        return (long) u16(b, off) << 16 | u16(b, off + 2);
    }

    private static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }
}
