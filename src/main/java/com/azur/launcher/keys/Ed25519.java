package com.azur.launcher.keys;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** JDK Ed25519 over raw 32-byte public keys (the form our Go tools print): decode, key id, verify against a list. */
public final class Ed25519 {

    /** DER prefix of an Ed25519 SubjectPublicKeyInfo; the 32 raw key bytes follow. */
    private static final byte[] SPKI_PREFIX = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};

    private Ed25519() {
    }

    /** The 32 raw bytes of a base64 key. @throws IllegalArgumentException when it is not one */
    public static byte[] raw(String base64) {
        byte[] raw = Base64.getDecoder().decode(base64.trim());
        if (raw.length != 32) {
            throw new IllegalArgumentException("Ed25519 public key must be 32 bytes");
        }
        return raw;
    }

    public static PublicKey publicKey(byte[] raw) throws GeneralSecurityException {
        if (Objects.isNull(raw) || raw.length != 32) {
            throw new GeneralSecurityException("Ed25519 public key must be 32 bytes");
        }
        byte[] der = new byte[SPKI_PREFIX.length + 32];
        System.arraycopy(SPKI_PREFIX, 0, der, 0, SPKI_PREFIX.length);
        System.arraycopy(raw, 0, der, SPKI_PREFIX.length, 32);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
    }

    /** The raw 32 bytes of a JDK Ed25519 public key (tests, key ids). */
    public static byte[] raw(PublicKey key) {
        byte[] der = key.getEncoded();
        byte[] raw = new byte[32];
        System.arraycopy(der, der.length - 32, raw, 0, 32);
        return raw;
    }

    /** Hex of the first 8 bytes of SHA-256 of the raw key (same as Go {@code keyset.KeyID}). */
    public static String keyId(byte[] raw) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(raw);
            return HexFormat.of().formatHex(h, 0, 8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean verify(PublicKey key, byte[] message, byte[] signature) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(message);
            return verifier.verify(signature);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    public static boolean verify(byte[] rawKey, byte[] message, byte[] signature) {
        try {
            return verify(publicKey(rawKey), message, signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /** True when any of the keys made the signature. */
    public static boolean verifyAny(List<PublicKey> keys, byte[] message, byte[] signature) {
        for (PublicKey key : keys) {
            if (verify(key, message, signature)) {
                return true;
            }
        }
        return false;
    }

    /** JDK keys from raw ones; a malformed entry is skipped. */
    public static List<PublicKey> publicKeys(List<byte[]> raws) {
        return raws.stream().map(r -> {
            try {
                return publicKey(r);
            } catch (GeneralSecurityException e) {
                return null;
            }
        }).filter(java.util.Objects::nonNull).toList();
    }
}
