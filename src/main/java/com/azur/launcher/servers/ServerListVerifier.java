package com.azur.launcher.servers;

import com.azur.launcher.keys.Ed25519;
import com.azur.launcher.pack.PackSource;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Checks the signed envelope {@code {"payload": base64(json), "sig": base64(ed25519)}} written by
 * {@code azur-servers sign}. The signature covers the exact payload bytes (canonical JSON), so there is
 * nothing to re-canonicalise here. Uses the JDK's own Ed25519.
 */
public final class ServerListVerifier {

    public static final int SCHEMA = 2;
    private static final int MAX_BYTES = 1 << 20;
    private static final Gson GSON = new Gson();

    private ServerListVerifier() {
    }

    /** Builds a key from its base64 raw 32-byte form (what {@code azur-servers pubkey} prints). */
    public static PublicKey publicKey(String base64) throws GeneralSecurityException {
        try {
            return Ed25519.publicKey(Ed25519.raw(base64));
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException(e.getMessage());
        }
    }

    /** Verifies signature, format and expiry. */
    public static ServerList open(byte[] envelope, PublicKey key, Instant now) throws ServerListException {
        return open(envelope, List.of(key), now, false);
    }

    /** Same with any of {@code keys} (the servers keys of the keyset). */
    public static ServerList open(byte[] envelope, List<PublicKey> keys, Instant now) throws ServerListException {
        return open(envelope, keys, now, false);
    }

    /** Same, but an expired (yet correctly signed) file is accepted; used for the cached copy only. */
    public static ServerList openIgnoringExpiry(byte[] envelope, List<PublicKey> keys, Instant now)
            throws ServerListException {
        return open(envelope, keys, now, true);
    }

    private static ServerList open(byte[] envelope, List<PublicKey> keys, Instant now, boolean ignoreExpiry)
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
        if (!Ed25519.verifyAny(keys, payload, sig)) {
            throw new ServerListException(ServerListException.Reason.BAD_SIGNATURE, "bad signature");
        }
        ServerList list = parse(payload);
        if (!ignoreExpiry && now.isAfter(list.validUntil())) {
            throw new ServerListException(ServerListException.Reason.EXPIRED, "expired " + list.validUntil());
        }
        return list;
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
                nz(raw.neoforgeSha256()).toLowerCase(Locale.ROOT), nz(raw.java()), nz(raw.packChannel()),
                Objects.isNull(raw.pack()) ? PackSource.NONE : raw.pack(), nz(raw.motd()), nz(raw.mirror()).trim());
    }

    private static boolean blank(String s) {
        return Objects.isNull(s) || s.isBlank();
    }

    private static String nz(String s) {
        return Objects.isNull(s) ? "" : s;
    }
}
