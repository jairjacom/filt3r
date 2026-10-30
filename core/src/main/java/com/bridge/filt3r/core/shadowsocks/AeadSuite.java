package com.bridge.filt3r.core.shadowsocks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** The Shadowsocks AEAD ciphers we support, plus the key derivation they share. */
public enum AeadSuite {
    AES_128_GCM("aes-128-gcm", 16, "AES/GCM/NoPadding", "AES"),
    AES_256_GCM("aes-256-gcm", 32, "AES/GCM/NoPadding", "AES"),
    CHACHA20_IETF_POLY1305("chacha20-ietf-poly1305", 32, "ChaCha20-Poly1305", "ChaCha20");

    public static final int TAG_LEN = 16;
    public static final int NONCE_LEN = 12;

    public final String methodName;
    public final int keyLen;
    private final String transformation;
    private final String keyAlgorithm;

    AeadSuite(String methodName, int keyLen, String transformation, String keyAlgorithm) {
        this.methodName = methodName;
        this.keyLen = keyLen;
        this.transformation = transformation;
        this.keyAlgorithm = keyAlgorithm;
    }

    public static AeadSuite fromMethod(String name) {
        for (AeadSuite s : values()) {
            if (s.methodName.equalsIgnoreCase(name)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unsupported method: " + name);
    }

    /** The master key from a password: OpenSSL EVP_BytesToKey with MD5, one round, no salt. */
    public byte[] masterKey(String password) {
        byte[] pw = password.getBytes(StandardCharsets.UTF_8);
        byte[] key = new byte[keyLen];
        byte[] prev = new byte[0];
        int filled = 0;
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            while (filled < keyLen) {
                md5.update(prev);
                md5.update(pw);
                prev = md5.digest();
                int n = Math.min(prev.length, keyLen - filled);
                System.arraycopy(prev, 0, key, filled, n);
                filled += n;
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        return key;
    }

    /** A per-connection encrypt or decrypt state: sub-key from HKDF-SHA1(master, salt) and a counter nonce. */
    public Session session(byte[] masterKey, byte[] salt) {
        return new Session(hkdfSha1(masterKey, salt, "ss-subkey".getBytes(StandardCharsets.US_ASCII), keyLen));
    }

    static byte[] hkdfSha1(byte[] ikm, byte[] salt, byte[] info, int length) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(salt, "HmacSHA1"));
            byte[] prk = mac.doFinal(ikm);
            mac.init(new SecretKeySpec(prk, "HmacSHA1"));
            byte[] out = new byte[length];
            byte[] t = new byte[0];
            int filled = 0;
            for (int i = 1; filled < length; i++) {
                mac.update(t);
                mac.update(info);
                mac.update((byte) i);
                t = mac.doFinal();
                int n = Math.min(t.length, length - filled);
                System.arraycopy(t, 0, out, filled, n);
                filled += n;
            }
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Not thread safe: one Session per direction of one connection. */
    public final class Session {
        private final SecretKeySpec key;
        private final byte[] nonce = new byte[NONCE_LEN];
        private final Cipher cipher;

        private Session(byte[] subKey) {
            try {
                this.key = new SecretKeySpec(subKey, keyAlgorithm);
                this.cipher = Cipher.getInstance(transformation);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(e);
            }
        }

        /** @return ciphertext followed by the 16-byte tag */
        public byte[] seal(byte[] plain, int off, int len) throws IOException {
            try {
                cipher.init(Cipher.ENCRYPT_MODE, key, params());
                byte[] out = cipher.doFinal(plain, off, len);
                increment();
                return out;
            } catch (GeneralSecurityException e) {
                throw new IOException("encrypt failed", e);
            }
        }

        /** @param data ciphertext followed by the 16-byte tag; @throws IOException if authentication fails */
        public byte[] open(byte[] data, int off, int len) throws IOException {
            try {
                cipher.init(Cipher.DECRYPT_MODE, key, params());
                byte[] out = cipher.doFinal(data, off, len);
                increment();
                return out;
            } catch (AEADBadTagException e) {
                throw new IOException("authentication failed", e);
            } catch (GeneralSecurityException e) {
                throw new IOException("decrypt failed", e);
            }
        }

        private java.security.spec.AlgorithmParameterSpec params() {
            byte[] iv = nonce.clone();
            return keyAlgorithm.equals("AES") ? new GCMParameterSpec(TAG_LEN * 8, iv) : new IvParameterSpec(iv);
        }

        private void increment() {
            for (int i = 0; i < nonce.length; i++) { // little endian counter
                if (++nonce[i] != 0) {
                    return;
                }
            }
        }
    }
}
