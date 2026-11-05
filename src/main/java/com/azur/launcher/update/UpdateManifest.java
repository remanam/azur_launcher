package com.azur.launcher.update;

import com.google.gson.Gson;
import com.azur.launcher.keys.Ed25519;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * {@code launcher.json}: version, sha256, size, date, url, min_wrapper, required, notes, signature (legacy, servers key)
 * and {@code signatures} (keyset launcher keys, docs/KEYSET.md). Every Ed25519 signature covers {@link #signedMessage()}, a fixed line format identical to the Go
 * signer ({@code tools/updater/internal/launcherman}), so no JSON canonicalisation is involved.
 */
public record UpdateManifest(String version, String sha256, long size, String date, String url,
                             String minWrapper, boolean required, String notes) {

    private static final int MAX_BYTES = 64 * 1024;
    private static final Gson GSON = new Gson();

    /** The exact bytes that are signed. */
    String signedMessage() {
        String notesHash;
        try {
            notesHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(notes.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        return String.join("\n", "azur-launcher-update/1", version, sha256, Long.toString(size), url, minWrapper,
                Boolean.toString(required), date, notesHash);
    }

    /** Legacy path: parses and verifies the single {@code signature} field (jars without root keys). */
    public static UpdateManifest open(byte[] raw, PublicKey key) throws UpdateException {
        JsonObject doc = json(raw);
        UpdateManifest manifest = fields(doc);
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(doc.get("signature").getAsString());
        } catch (RuntimeException e) {
            throw new UpdateException("not a launcher manifest");
        }
        if (!Ed25519.verify(key, manifest.signedMessage().getBytes(StandardCharsets.UTF_8), signature)) {
            throw new UpdateException("bad signature");
        }
        manifest.checkFields();
        return manifest;
    }

    /**
     * Keyset path: one entry of {@code signatures[{key_id, sig}]} must verify with one of {@code keys} (the valid
     * {@code launcher} keys of the keyset). The legacy {@code signature} field is ignored here.
     */
    public static UpdateManifest openWithKeys(byte[] raw, List<PublicKey> keys) throws UpdateException {
        JsonObject doc = json(raw);
        UpdateManifest manifest = fields(doc);
        byte[] message = manifest.signedMessage().getBytes(StandardCharsets.UTF_8);
        boolean ok = false;
        if (doc.has("signatures") && doc.get("signatures").isJsonArray()) {
            for (JsonElement e : doc.getAsJsonArray("signatures")) {
                try {
                    byte[] sig = Base64.getDecoder().decode(e.getAsJsonObject().get("sig").getAsString());
                    ok = ok || Ed25519.verifyAny(keys, message, sig);
                } catch (RuntimeException ignored) {
                    // a malformed entry does not count
                }
            }
        }
        if (!ok) {
            throw new UpdateException("no valid signature by a launcher key of the keyset");
        }
        manifest.checkFields();
        return manifest;
    }

    private static JsonObject json(byte[] raw) throws UpdateException {
        if (raw.length > MAX_BYTES) {
            throw new UpdateException("manifest too large");
        }
        try {
            return GSON.fromJson(new String(raw, StandardCharsets.UTF_8), JsonObject.class);
        } catch (JsonParseException e) {
            throw new UpdateException("not a launcher manifest");
        }
    }

    private static UpdateManifest fields(JsonObject doc) throws UpdateException {
        try {
            return new UpdateManifest(text(doc, "version"), text(doc, "sha256").toLowerCase(Locale.ROOT),
                    doc.get("size").getAsLong(), text(doc, "date"), text(doc, "url"), text(doc, "min_wrapper"),
                    doc.get("required").getAsBoolean(), doc.has("notes") ? doc.get("notes").getAsString() : "");
        } catch (IllegalArgumentException | IllegalStateException | NullPointerException | ClassCastException
                 | UnsupportedOperationException e) {
            throw new UpdateException("not a launcher manifest");
        }
    }

    private void checkFields() throws UpdateException {
        if (!sha256.matches("[0-9a-f]{64}") || size <= 0 || version.isBlank() || minWrapper.isBlank()) {
            throw new UpdateException("invalid manifest fields");
        }
        URI uri = URI.create(url);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new UpdateException("jar url must be plain https");
        }
    }

    private static String text(JsonObject doc, String name) {
        return doc.get(name).getAsString();
    }
}
