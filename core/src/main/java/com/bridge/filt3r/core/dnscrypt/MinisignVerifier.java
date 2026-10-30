package com.bridge.filt3r.core.dnscrypt;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Checks a minisign signature in the plain "Ed" mode (Ed25519 over the whole file), which is what
 * the DNSCrypt resolver list uses. The prehashed "ED" mode is not supported and is rejected.
 */
public final class MinisignVerifier {

    private static final byte[] ED25519_PREFIX = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};

    private MinisignVerifier() {
    }

    /**
     * @param minisig         the .minisig file text (second line is the signature)
     * @param publicKeyBase64 the minisign public key (42 bytes: "Ed", key id, key)
     * @return true only if the signature is valid for this key
     */
    public static boolean verify(byte[] data, String minisig, String publicKeyBase64) {
        try {
            String[] lines = minisig.split("\\r?\\n");
            if (lines.length < 2) {
                return false;
            }
            byte[] sig = Base64.getDecoder().decode(lines[1].trim());
            byte[] pk = Base64.getDecoder().decode(publicKeyBase64.trim());
            if (sig.length != 74 || pk.length != 42 || sig[0] != 'E' || sig[1] != 'd' || pk[0] != 'E' || pk[1] != 'd'
                    || !Arrays.equals(Arrays.copyOfRange(sig, 2, 10), Arrays.copyOfRange(pk, 2, 10))) {
                return false;
            }
            byte[] x509 = Arrays.copyOf(ED25519_PREFIX, ED25519_PREFIX.length + 32);
            System.arraycopy(pk, 10, x509, ED25519_PREFIX.length, 32);
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(x509));
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(data);
            return s.verify(Arrays.copyOfRange(sig, 10, 74));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
