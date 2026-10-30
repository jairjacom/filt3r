package com.bridge.filt3r.core.dnscrypt;

import java.io.IOException;
import java.math.BigInteger;
import java.security.MessageDigest;

/**
 * The XChaCha20-Poly1305 "secretbox" that DNSCrypt uses (es-version 2). It is not the RFC 8439
 * AEAD: the first 32 bytes of the key stream are the Poly1305 key, the message is encrypted with
 * the rest of the stream, and the tag covers the ciphertext only. The output is tag(16) followed
 * by the ciphertext. The platform has no raw ChaCha20 core or Poly1305, so both are written here.
 */
final class XChaChaBox {

    static final int KEY_LEN = 32;
    static final int NONCE_LEN = 24;
    static final int TAG_LEN = 16;

    private static final int[] SIGMA = {0x61707865, 0x3320646e, 0x79622d32, 0x6b206574};
    private static final BigInteger P1305 = BigInteger.ONE.shiftLeft(130).subtract(BigInteger.valueOf(5));
    private static final BigInteger MASK128 = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE);
    private static final BigInteger CLAMP = new BigInteger("0ffffffc0ffffffc0ffffffc0fffffff", 16);

    private XChaChaBox() {
    }

    /** @return tag followed by ciphertext */
    static byte[] seal(byte[] plain, byte[] nonce, byte[] key) {
        byte[] stream = keyStream(key, nonce, 32 + plain.length);
        byte[] out = new byte[TAG_LEN + plain.length];
        for (int i = 0; i < plain.length; i++) {
            out[TAG_LEN + i] = (byte) (plain[i] ^ stream[32 + i]);
        }
        byte[] tag = poly1305(java.util.Arrays.copyOf(stream, 32), out, TAG_LEN, plain.length);
        System.arraycopy(tag, 0, out, 0, TAG_LEN);
        return out;
    }

    /** @param box tag followed by ciphertext; @throws IOException if the tag does not match */
    static byte[] open(byte[] box, int off, int len, byte[] nonce, byte[] key) throws IOException {
        if (len < TAG_LEN) {
            throw new IOException("DNSCrypt response too short");
        }
        int n = len - TAG_LEN;
        byte[] stream = keyStream(key, nonce, 32 + n);
        byte[] tag = poly1305(java.util.Arrays.copyOf(stream, 32), box, off + TAG_LEN, n);
        if (!MessageDigest.isEqual(tag, java.util.Arrays.copyOfRange(box, off, off + TAG_LEN))) {
            throw new IOException("DNSCrypt authentication failed");
        }
        byte[] plain = new byte[n];
        for (int i = 0; i < n; i++) {
            plain[i] = (byte) (box[off + TAG_LEN + i] ^ stream[32 + i]);
        }
        return plain;
    }

    /** The DNSCrypt shared key: HChaCha20 of the X25519 secret with an all-zero nonce. */
    static byte[] sharedKey(byte[] x25519Secret) {
        return hChaCha20(x25519Secret, new byte[16]);
    }

    /** XChaCha20 key stream: HChaCha20 sub-key, then ChaCha20 with nonce 0000 + last 8 bytes, block counter 0. */
    private static byte[] keyStream(byte[] key, byte[] nonce, int length) {
        byte[] subKey = hChaCha20(key, java.util.Arrays.copyOf(nonce, 16));
        int[] state = new int[16];
        System.arraycopy(SIGMA, 0, state, 0, 4);
        for (int i = 0; i < 8; i++) {
            state[4 + i] = le32(subKey, 4 * i);
        }
        state[12] = 0;
        state[13] = 0;
        state[14] = le32(nonce, 16);
        state[15] = le32(nonce, 20);
        byte[] out = new byte[(length + 63) / 64 * 64];
        for (int block = 0; block * 64 < length; block++) {
            state[12] = block;
            int[] x = state.clone();
            rounds(x);
            for (int i = 0; i < 16; i++) {
                putLe32(out, block * 64 + 4 * i, x[i] + state[i]);
            }
        }
        return out;
    }

    /** @param nonce 16 bytes */
    static byte[] hChaCha20(byte[] key, byte[] nonce) {
        int[] x = new int[16];
        System.arraycopy(SIGMA, 0, x, 0, 4);
        for (int i = 0; i < 8; i++) {
            x[4 + i] = le32(key, 4 * i);
        }
        for (int i = 0; i < 4; i++) {
            x[12 + i] = le32(nonce, 4 * i);
        }
        rounds(x);
        byte[] out = new byte[32];
        for (int i = 0; i < 4; i++) {
            putLe32(out, 4 * i, x[i]);
            putLe32(out, 16 + 4 * i, x[12 + i]);
        }
        return out;
    }

    private static void rounds(int[] x) {
        for (int i = 0; i < 10; i++) {
            quarter(x, 0, 4, 8, 12);
            quarter(x, 1, 5, 9, 13);
            quarter(x, 2, 6, 10, 14);
            quarter(x, 3, 7, 11, 15);
            quarter(x, 0, 5, 10, 15);
            quarter(x, 1, 6, 11, 12);
            quarter(x, 2, 7, 8, 13);
            quarter(x, 3, 4, 9, 14);
        }
    }

    private static void quarter(int[] x, int a, int b, int c, int d) {
        x[a] += x[b];
        x[d] = Integer.rotateLeft(x[d] ^ x[a], 16);
        x[c] += x[d];
        x[b] = Integer.rotateLeft(x[b] ^ x[c], 12);
        x[a] += x[b];
        x[d] = Integer.rotateLeft(x[d] ^ x[a], 8);
        x[c] += x[d];
        x[b] = Integer.rotateLeft(x[b] ^ x[c], 7);
    }

    /** Poly1305 with BigInteger arithmetic: DNS packets are small, so speed does not matter. */
    static byte[] poly1305(byte[] key, byte[] msg, int off, int len) {
        BigInteger r = leNumber(key, 0, 16).and(CLAMP);
        BigInteger s = leNumber(key, 16, 16);
        BigInteger acc = BigInteger.ZERO;
        for (int pos = 0; pos < len; pos += 16) {
            int n = Math.min(16, len - pos);
            BigInteger block = leNumber(msg, off + pos, n).setBit(8 * n);
            acc = acc.add(block).multiply(r).mod(P1305);
        }
        BigInteger tag = acc.add(s).and(MASK128);
        byte[] out = new byte[16];
        byte[] be = tag.toByteArray();
        for (int i = 0; i < 16 && i < be.length; i++) { // be is big endian, possibly with a sign byte
            out[i] = be[be.length - 1 - i];
        }
        return out;
    }

    private static BigInteger leNumber(byte[] b, int off, int len) {
        byte[] be = new byte[len + 1]; // leading zero keeps the value positive
        for (int i = 0; i < len; i++) {
            be[len - i] = b[off + i];
        }
        return new BigInteger(be);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8 | (b[off + 2] & 0xFF) << 16 | (b[off + 3] & 0xFF) << 24;
    }

    private static void putLe32(byte[] b, int off, int v) {
        b[off] = (byte) v;
        b[off + 1] = (byte) (v >>> 8);
        b[off + 2] = (byte) (v >>> 16);
        b[off + 3] = (byte) (v >>> 24);
    }
}
