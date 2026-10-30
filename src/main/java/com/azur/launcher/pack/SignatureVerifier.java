package com.azur.launcher.pack;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Opens the signed manifest envelope {@code {payload, sig, key_id}}. The signed bytes are exactly the base64-decoded
 * {@code payload} (the publisher's JSON as it was signed, no re-serialisation); the signature is Ed25519 over them
 * with the pack key from the signed server list.
 */
public final class SignatureVerifier {

    public static final int MAX_MANIFEST = 4 << 20;
    /** DER prefix of an Ed25519 SubjectPublicKeyInfo; the 32 raw key bytes follow. */
    private static final byte[] SPKI_PREFIX = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
    private static final Gson GSON = new Gson();

    private record Envelope(String payload, String sig, @SerializedName("key_id") String keyId) {
    }

    private SignatureVerifier() {
    }

    /** @throws PackException code {@code signature} when the signature or the manifest is wrong */
    public static PackManifest open(byte[] data, byte[] publicKey) throws PackException {
        if (data.length > MAX_MANIFEST) {
            throw wrong("the manifest is too large");
        }
        byte[] raw;
        try {
            Envelope env = GSON.fromJson(new String(data, StandardCharsets.UTF_8), Envelope.class);
            raw = Base64.getDecoder().decode(env.payload());
            byte[] sig = Base64.getDecoder().decode(env.sig());
            if (!verify(publicKey, raw, sig)) {
                throw wrong("signature check failed: wrong server or the key changed");
            }
        } catch (IllegalArgumentException | NullPointerException | JsonSyntaxException e) {
            throw wrong("the manifest is damaged");
        }
        try {
            PackManifest manifest = GSON.fromJson(new String(raw, StandardCharsets.UTF_8), PackManifest.class);
            if (manifest == null) {
                throw wrong("the manifest is empty");
            }
            manifest.validate();
            return manifest;
        } catch (JsonSyntaxException e) {
            throw wrong("the manifest is damaged");
        }
    }

    public static boolean verify(byte[] publicKey, byte[] message, byte[] signature) {
        if (publicKey == null || publicKey.length != 32) {
            return false;
        }
        try {
            byte[] spki = new byte[SPKI_PREFIX.length + 32];
            System.arraycopy(SPKI_PREFIX, 0, spki, 0, SPKI_PREFIX.length);
            System.arraycopy(publicKey, 0, spki, SPKI_PREFIX.length, 32);
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(spki));
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(message);
            return s.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    private static PackException wrong(String message) {
        return new PackException(PackException.SIGNATURE, message);
    }
}
