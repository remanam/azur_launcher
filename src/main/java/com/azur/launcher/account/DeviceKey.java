package com.azur.launcher.account;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * The Ed25519 key of this device (JDK only). The private part is a 32-byte seed; the site gets only the raw 32-byte
 * public key (base64url, no padding), exactly like the server-side check in azur_core {@code LoginProof}.
 */
public final class DeviceKey {

    private static final byte[] PKCS8_PREFIX = {0x30, 0x2e, 0x02, 0x01, 0x00, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x04, 0x22, 0x04, 0x20};
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private final byte[] seed;
    private final byte[] publicKey;
    private final PrivateKey privateKey;

    private DeviceKey(byte[] seed, byte[] publicKey, PrivateKey privateKey) {
        this.seed = seed;
        this.publicKey = publicKey;
        this.privateKey = privateKey;
    }

    public static DeviceKey generate() {
        try {
            KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            byte[] pkcs8 = pair.getPrivate().getEncoded();
            byte[] x509 = pair.getPublic().getEncoded();
            return new DeviceKey(Arrays.copyOfRange(pkcs8, pkcs8.length - 32, pkcs8.length),
                    Arrays.copyOfRange(x509, x509.length - 32, x509.length), pair.getPrivate());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Ed25519 is not available", e);
        }
    }

    /**
     * Restores a key. The JDK cannot derive the public key from the seed, so both halves are stored together.
     *
     * @throws IllegalArgumentException when the seed is malformed or does not match the public key
     */
    public static DeviceKey restore(String seedBase64Url, String publicBase64Url) {
        try {
            byte[] seed = Base64.getUrlDecoder().decode(seedBase64Url);
            byte[] pub = Base64.getUrlDecoder().decode(publicBase64Url);
            if (seed.length != 32 || pub.length != 32) {
                throw new IllegalArgumentException("bad key length");
            }
            byte[] pkcs8 = new byte[PKCS8_PREFIX.length + 32];
            System.arraycopy(PKCS8_PREFIX, 0, pkcs8, 0, PKCS8_PREFIX.length);
            System.arraycopy(seed, 0, pkcs8, PKCS8_PREFIX.length, 32);
            DeviceKey key = new DeviceKey(seed, pub, KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(pkcs8)));
            if (!key.selfCheck()) {
                throw new IllegalArgumentException("seed and public key do not match");
            }
            return key;
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("bad device key", e);
        }
    }

    private boolean selfCheck() throws GeneralSecurityException {
        byte[] probe = {1, 2, 3};
        byte[] x509Prefix = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
        byte[] x509 = new byte[x509Prefix.length + 32];
        System.arraycopy(x509Prefix, 0, x509, 0, x509Prefix.length);
        System.arraycopy(publicKey, 0, x509, x509Prefix.length, 32);
        Signature v = Signature.getInstance("Ed25519");
        v.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new java.security.spec.X509EncodedKeySpec(x509)));
        v.update(probe);
        return v.verify(sign(probe));
    }

    public String publicKeyBase64Url() {
        return B64.encodeToString(publicKey);
    }

    public String seedBase64Url() {
        return B64.encodeToString(seed);
    }

    public byte[] sign(byte[] message) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(privateKey);
            s.update(message);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Same digest as the site: sha256 of the raw public key, first 8 bytes, hex in four groups. */
    public String fingerprint() {
        return fingerprintOf(publicKey);
    }

    public static String fingerprintOf(byte[] rawPublic) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(rawPublic);
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", h[i]));
            }
            return hex.substring(0, 4) + "-" + hex.substring(4, 8) + "-" + hex.substring(8, 12) + "-" + hex.substring(12, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
