package com.azur.launcher.update;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * {@code launcher.json}: version, sha256, size, date, url, min_wrapper, required, notes, signature. The Ed25519
 * signature (the key of servers.json) covers {@link #signedMessage()}, a fixed line format identical to the Go
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

    /** Parses and verifies; throws {@link UpdateException} for a bad format or signature. */
    public static UpdateManifest open(byte[] raw, PublicKey key) throws UpdateException {
        if (raw.length > MAX_BYTES) {
            throw new UpdateException("manifest too large");
        }
        UpdateManifest manifest;
        byte[] signature;
        try {
            JsonObject doc = GSON.fromJson(new String(raw, StandardCharsets.UTF_8), JsonObject.class);
            manifest = new UpdateManifest(text(doc, "version"), text(doc, "sha256").toLowerCase(Locale.ROOT),
                    doc.get("size").getAsLong(), text(doc, "date"), text(doc, "url"), text(doc, "min_wrapper"),
                    doc.get("required").getAsBoolean(), doc.has("notes") ? doc.get("notes").getAsString() : "");
            signature = Base64.getDecoder().decode(text(doc, "signature"));
        } catch (JsonParseException | IllegalArgumentException | IllegalStateException | NullPointerException
                 | ClassCastException e) {
            throw new UpdateException("not a launcher manifest");
        }
        if (!signatureValid(manifest.signedMessage().getBytes(StandardCharsets.UTF_8), signature, key)) {
            throw new UpdateException("bad signature");
        }
        manifest.checkFields();
        return manifest;
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

    private static boolean signatureValid(byte[] message, byte[] signature, PublicKey key) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(message);
            return verifier.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
