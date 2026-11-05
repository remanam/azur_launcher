package com.azur.launcher.keys;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The root-signed list of working keys ({@code keyset.json}, format {@code docs/KEYSET.md}). Twin of Go
 * {@code tools/updater/internal/keyset}; keep the rules identical:
 * <ul>
 *   <li>signed by the reserve root: may change everything (epoch, root-role keys, root revocations);</li>
 *   <li>signed by the primary root (or a root-role key of the previous keyset): same epoch and the same root section
 *       as the previous keyset (none without one), only working keys change;</li>
 *   <li>(epoch, version) never goes down; the same pair must carry the same payload (anti-rollback);</li>
 *   <li>root revocations are cumulative; an expired keyset is refused; one public key appears at most once.</li>
 * </ul>
 */
public final class Keyset {

    public static final String TYPE = "azur-keyset/1";
    public static final String SERVERS = "servers";
    public static final String PACK = "pack";
    public static final String LAUNCHER = "launcher";
    public static final String ROOT = "root";
    private static final Set<String> ROLES = Set.of(SERVERS, PACK, LAUNCHER, ROOT);
    private static final int MAX_BYTES = 256 * 1024;
    private static final Gson GSON = new Gson();

    /** One working key. */
    public record WorkingKey(String id, String role, String base64, byte[] raw, Instant expiresAt) {
    }

    private final long epoch;
    private final long version;
    private final Instant expiresAt;
    private final List<WorkingKey> keys;
    private final Set<String> revoked;
    private final byte[] payload;
    private final boolean byReserve;

    private Keyset(long epoch, long version, Instant expiresAt, List<WorkingKey> keys, Set<String> revoked,
                   byte[] payload, boolean byReserve) {
        this.epoch = epoch;
        this.version = version;
        this.expiresAt = expiresAt;
        this.keys = keys;
        this.revoked = revoked;
        this.payload = payload;
        this.byReserve = byReserve;
    }

    public long epoch() {
        return epoch;
    }

    public long version() {
        return version;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public boolean byReserve() {
        return byReserve;
    }

    public boolean expired(Instant now) {
        return now.isAfter(expiresAt);
    }

    /** Usable raw keys of a role at {@code now}: listed, not revoked, not expired, keyset not expired. */
    public List<byte[]> keys(String role, Instant now) {
        return expired(now) ? List.of() : listed(role, now);
    }

    /** Same, ignoring the keyset's own expiry (root-role signers of the previous keyset). */
    private List<byte[]> listed(String role, Instant now) {
        List<byte[]> out = new ArrayList<>();
        for (WorkingKey k : keys) {
            if (k.role().equals(role) && !isRevoked(k) && !now.isAfter(k.expiresAt())) {
                out.add(k.raw());
            }
        }
        return out;
    }

    private boolean isRevoked(WorkingKey k) {
        return revoked.contains(k.id()) || revoked.contains(k.base64()) || revoked.contains(Ed25519.keyId(k.raw()));
    }

    private boolean revokes(byte[] raw) {
        return raw != null && (revoked.contains(Ed25519.keyId(raw)) || revoked.contains(Base64.getEncoder().encodeToString(raw)));
    }

    /**
     * Verifies {@code raw} against the embedded roots and the previously accepted keyset ({@code null} on first contact).
     */
    public static Keyset open(byte[] raw, RootKeys roots, Keyset prev, Instant now) throws KeysetException {
        if (!roots.present()) {
            throw new KeysetException(KeysetException.Reason.BAD_FORMAT, "no root keys in this build");
        }
        Parsed p = parse(raw);
        Keyset doc = p.keyset(false);
        boolean byReserve = signedBy(p, roots.reserve());
        boolean byRoot = byReserve;
        if (!byRoot) {
            for (byte[] k : primarySigners(roots, prev, now)) {
                if (signedBy(p, k)) {
                    byRoot = true;
                    break;
                }
            }
        }
        if (!byRoot) {
            throw new KeysetException(KeysetException.Reason.BAD_SIGNATURE, "no valid root signature");
        }
        if (doc.lists(roots.primary()) || doc.lists(roots.reserve())) {
            throw new KeysetException(KeysetException.Reason.BAD_FORMAT, "an embedded root key is listed as a working key");
        }
        if (doc.revokes(roots.reserve())) {
            throw new KeysetException(KeysetException.Reason.BAD_FORMAT, "the reserve root cannot be revoked");
        }
        long prevEpoch = 0;
        String prevRoot = "";
        if (prev != null) {
            prevEpoch = prev.epoch;
            prevRoot = prev.rootSection(roots);
            if (doc.epoch < prev.epoch || (doc.epoch == prev.epoch && doc.version < prev.version)) {
                throw new KeysetException(KeysetException.Reason.ROLLBACK,
                        "version " + doc.epoch + "." + doc.version + " is older than " + prev.epoch + "." + prev.version);
            }
            if (doc.epoch == prev.epoch && doc.version == prev.version && !Arrays.equals(doc.payload, prev.payload)) {
                throw new KeysetException(KeysetException.Reason.ROLLBACK, "same version, different content");
            }
            for (String r : prev.rootRevocations(roots)) {
                if (!doc.revoked.contains(r)) {
                    throw new KeysetException(KeysetException.Reason.BAD_FORMAT, "root revocation dropped");
                }
            }
        }
        if (!byReserve && (doc.epoch != prevEpoch || !doc.rootSection(roots).equals(prevRoot))) {
            throw new KeysetException(KeysetException.Reason.NEEDS_RESERVE,
                    "epoch or root keys changed without the reserve root signature");
        }
        if (doc.expired(now)) {
            throw new KeysetException(KeysetException.Reason.EXPIRED, "keyset expired " + doc.expiresAt);
        }
        return p.keyset(byReserve);
    }

    /**
     * Reads the cached copy without checking signatures: it was verified by {@link #open} before it was written to the
     * user's own data folder. Used only as the anti-rollback reference and the trusted state at start.
     */
    public static Keyset trustedCache(byte[] raw) throws KeysetException {
        return parse(raw).keyset(false);
    }

    private static List<byte[]> primarySigners(RootKeys roots, Keyset prev, Instant now) {
        List<byte[]> out = new ArrayList<>();
        if (prev == null || !prev.revokes(roots.primary())) {
            out.add(roots.primary());
        }
        if (prev != null) {
            out.addAll(prev.listed(ROOT, now));
        }
        return out;
    }

    private static boolean signedBy(Parsed p, byte[] key) {
        if (key == null) {
            return false;
        }
        for (byte[] sig : p.sigs) {
            if (Ed25519.verify(key, p.payload, sig)) {
                return true;
            }
        }
        return false;
    }

    private boolean lists(byte[] raw) {
        return keys.stream().anyMatch(k -> Arrays.equals(k.raw(), raw));
    }

    private Set<String> rootNames(RootKeys roots) {
        Set<String> names = new HashSet<>(Set.of(Ed25519.keyId(roots.primary()), Base64.getEncoder().encodeToString(roots.primary())));
        for (WorkingKey k : keys) {
            if (k.role().equals(ROOT)) {
                names.add(k.id());
                names.add(k.base64());
                names.add(Ed25519.keyId(k.raw()));
            }
        }
        return names;
    }

    private List<String> rootRevocations(RootKeys roots) {
        Set<String> names = rootNames(roots);
        return revoked.stream().filter(names::contains).sorted().toList();
    }

    private String rootSection(RootKeys roots) {
        TreeSet<String> parts = new TreeSet<>();
        for (WorkingKey k : keys) {
            if (k.role().equals(ROOT)) {
                parts.add("key " + k.id() + " " + k.base64() + " " + k.expiresAt());
            }
        }
        List<String> all = new ArrayList<>(parts);
        all.addAll(rootRevocations(roots));
        return String.join("\n", all);
    }

    // ------------------------------------------------------------------ parsing

    private record Parsed(byte[] payload, List<byte[]> sigs, long epoch, long version, Instant expiresAt,
                          List<WorkingKey> keys, Set<String> revoked) {
        Keyset keyset(boolean byReserve) {
            return new Keyset(epoch, version, expiresAt, keys, revoked, payload, byReserve);
        }
    }

    private static Parsed parse(byte[] raw) throws KeysetException {
        if (raw == null || raw.length > MAX_BYTES) {
            throw bad("not a keyset envelope");
        }
        try {
            JsonObject env = GSON.fromJson(new String(raw, StandardCharsets.UTF_8), JsonObject.class);
            byte[] payload = Base64.getDecoder().decode(env.get("payload").getAsString());
            List<byte[]> sigs = new ArrayList<>();
            if (env.has("sigs") && env.get("sigs").isJsonArray()) {
                for (JsonElement s : env.getAsJsonArray("sigs")) {
                    try {
                        sigs.add(Base64.getDecoder().decode(s.getAsJsonObject().get("sig").getAsString()));
                    } catch (RuntimeException ignored) {
                        // a malformed signature entry simply does not count
                    }
                }
            }
            JsonObject doc = GSON.fromJson(new String(payload, StandardCharsets.UTF_8), JsonObject.class);
            if (!TYPE.equals(doc.get("type").getAsString())) {
                throw bad("type must be " + TYPE);
            }
            long epoch = doc.get("epoch").getAsLong();
            long version = doc.get("version").getAsLong();
            if (version < 1 || epoch < 0) {
                throw bad("version >= 1 and epoch >= 0 required");
            }
            Instant expires = Instant.parse(doc.get("expires_at").getAsString());
            List<WorkingKey> keys = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            Set<String> values = new HashSet<>();
            for (JsonElement e : doc.getAsJsonArray("keys")) {
                JsonObject k = e.getAsJsonObject();
                String id = k.get("id").getAsString();
                String role = k.get("role").getAsString();
                byte[] key = Ed25519.raw(k.get("key").getAsString());
                String b64 = Base64.getEncoder().encodeToString(key);
                if (id.isEmpty() || !ids.add(id) || !ROLES.contains(role) || !values.add(b64)) {
                    throw bad("key " + id + ": empty or repeated id, unknown role or a key listed twice");
                }
                keys.add(new WorkingKey(id, role, b64, key, Instant.parse(k.get("expires_at").getAsString())));
            }
            Set<String> revoked = new HashSet<>();
            if (doc.has("revoked") && doc.get("revoked").isJsonArray()) {
                for (JsonElement r : doc.getAsJsonArray("revoked")) {
                    revoked.add(r.getAsString());
                }
            }
            return new Parsed(payload, sigs, epoch, version, expires, List.copyOf(keys), Set.copyOf(revoked));
        } catch (KeysetException e) {
            throw e;
        } catch (JsonParseException | IllegalArgumentException | IllegalStateException | NullPointerException
                 | ClassCastException | UnsupportedOperationException | DateTimeParseException e) {
            throw bad("invalid keyset");
        }
    }

    private static KeysetException bad(String message) {
        return new KeysetException(KeysetException.Reason.BAD_FORMAT, message);
    }
}
