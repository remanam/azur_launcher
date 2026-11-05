package com.azur.launcher.pack;

import com.azur.launcher.keys.Ed25519;
import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * Opens the signed manifest envelope {@code {payload, sig, key_id}}. The signed bytes are exactly the base64-decoded
 * {@code payload} (the publisher's JSON as it was signed, no re-serialisation); the signature is Ed25519 over them
 * with a pack key of the keyset (or, legacy, the key of the signed server list entry).
 */
public final class SignatureVerifier {

    public static final int MAX_MANIFEST = 4 << 20;
    private static final Gson GSON = new Gson();

    private record Envelope(String payload, String sig, @SerializedName("key_id") String keyId) {
    }

    private SignatureVerifier() {
    }

    /** @throws PackException code {@code signature} when the signature or the manifest is wrong */
    public static PackManifest open(byte[] data, byte[] publicKey) throws PackException {
        return open(data, List.of(publicKey));
    }

    /** Same with any of {@code keys} (the pack keys of the keyset, or the one key of the server entry). */
    public static PackManifest open(byte[] data, List<byte[]> keys) throws PackException {
        if (data.length > MAX_MANIFEST) {
            throw wrong("the manifest is too large");
        }
        byte[] raw;
        try {
            Envelope env = GSON.fromJson(new String(data, StandardCharsets.UTF_8), Envelope.class);
            raw = Base64.getDecoder().decode(env.payload());
            byte[] sig = Base64.getDecoder().decode(env.sig());
            if (keys.stream().noneMatch(k -> verify(k, raw, sig))) {
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
        return Ed25519.verify(publicKey, message, signature);
    }

    private static PackException wrong(String message) {
        return new PackException(PackException.SIGNATURE, message);
    }
}
