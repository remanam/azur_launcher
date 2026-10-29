package com.azur.launcher.servers;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Checks the signed envelope {@code {"payload": base64(json), "sig": base64(ed25519)}} written by
 * {@code azur-servers sign}. The signature covers the exact payload bytes (canonical JSON), so there is
 * nothing to re-canonicalise here. Uses the JDK's own Ed25519.
 */
public final class ServerListVerifier {

    public static final int SCHEMA = 1;
    private static final int MAX_BYTES = 1 << 20;
    /** DER prefix of an Ed25519 SubjectPublicKeyInfo; the 32 raw key bytes follow. */
    private static final byte[] SPKI_PREFIX = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
    private static final Gson GSON = new Gson();

    private ServerListVerifier() {
    }

    /** Builds a key from its base64 raw 32-byte form (what {@code azur-servers pubkey} prints). */
    public static PublicKey publicKey(String base64) throws GeneralSecurityException {
        byte[] raw = Base64.getDecoder().decode(base64.trim());
        if (raw.length != 32) {
            throw new GeneralSecurityException("Ed25519 public key must be 32 bytes");
        }
        byte[] der = new byte[SPKI_PREFIX.length + 32];
        System.arraycopy(SPKI_PREFIX, 0, der, 0, SPKI_PREFIX.length);
        System.arraycopy(raw, 0, der, SPKI_PREFIX.length, 32);
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
    }

    /** Verifies signature, format and expiry. */
    public static ServerList open(byte[] envelope, PublicKey key, Instant now) throws ServerListException {
        return open(envelope, key, now, false);
    }

    /** Same, but an expired (yet correctly signed) file is accepted; used for the offline cache only. */
    public static ServerList openIgnoringExpiry(byte[] envelope, PublicKey key, Instant now)
            throws ServerListException {
        return open(envelope, key, now, true);
    }

    private static ServerList open(byte[] envelope, PublicKey key, Instant now, boolean ignoreExpiry)
            throws ServerListException {
        if (envelope.length > MAX_BYTES) {
            throw new ServerListException(ServerListException.Reason.BAD_FORMAT, "file too large");
        }
        byte[] payload;
        byte[] sig;
        try {
            JsonObject env = GSON.fromJson(new String(envelope, StandardCharsets.UTF_8), JsonObject.class);
            payload = Base64.getDecoder().decode(env.get("payload").getAsString());
            sig = Base64.getDecoder().decode(env.get("sig").getAsString());
        } catch (RuntimeException e) {
            throw new ServerListException(ServerListException.Reason.BAD_FORMAT, "not a signed envelope");
        }
        if (!signatureValid(payload, sig, key)) {
            throw new ServerListException(ServerListException.Reason.BAD_SIGNATURE, "bad signature");
        }
        ServerList list = parse(payload);
        if (!ignoreExpiry && now.isAfter(list.validUntil())) {
            throw new ServerListException(ServerListException.Reason.EXPIRED, "expired " + list.validUntil());
        }
        return list;
    }

    private static boolean signatureValid(byte[] payload, byte[] sig, PublicKey key) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(payload);
            return verifier.verify(sig);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    static ServerList parse(byte[] payload) throws ServerListException {
        try {
            JsonObject doc = GSON.fromJson(new String(payload, StandardCharsets.UTF_8), JsonObject.class);
            int schema = doc.get("schema").getAsInt();
            if (schema != SCHEMA) {
                throw new ServerListException(ServerListException.Reason.BAD_FORMAT, "unknown schema " + schema);
            }
            Instant validUntil = Instant.parse(doc.get("validUntil").getAsString());
            List<ServerEntry> servers = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (var element : doc.getAsJsonArray("servers")) {
                ServerEntry entry = normalise(GSON.fromJson(element, ServerEntry.class));
                if (!ids.add(entry.id())) {
                    throw new ServerListException(ServerListException.Reason.BAD_FORMAT, "duplicate id " + entry.id());
                }
                servers.add(entry);
            }
            return new ServerList(schema, validUntil, List.copyOf(servers));
        } catch (JsonParseException | IllegalStateException | NullPointerException | DateTimeParseException
                 | ClassCastException e) {
            throw new ServerListException(ServerListException.Reason.BAD_FORMAT, "invalid content");
        }
    }

    private static ServerEntry normalise(ServerEntry raw) throws ServerListException {
        if (blank(raw.id()) || blank(raw.name()) || blank(raw.address())) {
            throw new ServerListException(ServerListException.Reason.BAD_FORMAT, "server without id, name or address");
        }
        int port = raw.port() == 0 ? ServerEntry.DEFAULT_PORT : raw.port();
        if (port < 1 || port > 65535) {
            throw new ServerListException(ServerListException.Reason.BAD_FORMAT, "bad port for " + raw.id());
        }
        return new ServerEntry(raw.id(), raw.name(), raw.address(), port, nz(raw.mcVersion()), nz(raw.neoforge()),
                nz(raw.java()), nz(raw.packChannel()), nz(raw.packUrl()), raw.inviteRequired(), nz(raw.motd()));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
