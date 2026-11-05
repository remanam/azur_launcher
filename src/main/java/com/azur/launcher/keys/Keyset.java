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
 *   <li>any listed root may sign (embedded working/emergency roots and the delegated {@code root}/{@code root_emergency}
 *       keys of the previous keyset), unless the previous keyset or the document itself revokes it;</li>
 *   <li>signed by an emergency root: may change everything (epoch, delegated roots, root revocations, revoking another
 *       emergency root); at least one usable emergency root must remain;</li>
 *   <li>signed by a working root: same epoch and the same root section as the previous keyset (none without one),
 *       only working keys change;</li>
 *   <li>optional {@code anchors}: the emergency-signed keysets this one builds on, for launchers that missed them;</li>
 *   <li>(epoch, version) never goes down; the same pair must carry the same payload (anti-rollback);</li>
 *   <li>root revocations are cumulative; an expired keyset is refused; one public key appears at most once.</li>
 * </ul>
 */
public final class Keyset {

    public static final String TYPE = "azur-keyset/1";
    public static final String SERVERS = "servers";
    public static final String PACK = "pack";
    public static final String LAUNCHER = "launcher";
    /** Delegated working root (replacement for a revoked root A); only an emergency root may add it. */
    public static final String ROOT = "root";
    /** Delegated emergency root (replacement for a lost or revoked B or C); only an emergency root may add it. */
    public static final String ROOT_EMERGENCY = "root_emergency";
    private static final Set<String> ROLES = Set.of(SERVERS, PACK, LAUNCHER, ROOT, ROOT_EMERGENCY);
    private static final int MAX_BYTES = 256 * 1024;
    private static final int MAX_ANCHORS = 16;
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
    private final boolean byEmergency;
    private final boolean anchored;

    private Keyset(long epoch, long version, Instant expiresAt, List<WorkingKey> keys, Set<String> revoked,
                   byte[] payload, boolean byEmergency, boolean anchored) {
        this.epoch = epoch;
        this.version = version;
        this.expiresAt = expiresAt;
        this.keys = keys;
        this.revoked = revoked;
        this.payload = payload;
        this.byEmergency = byEmergency;
        this.anchored = anchored;
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

    /** Signed by an emergency root (embedded or delegated). */
    public boolean byEmergency() {
        return byEmergency;
    }

    /** Accepted through its embedded anchor chain (the launcher had missed an emergency keyset). */
    public boolean anchored() {
        return anchored;
    }

    public boolean expired(Instant now) {
        return now.isAfter(expiresAt);
    }

    /** Usable raw keys of a role at {@code now}: listed, not revoked, not expired, keyset not expired. */
    public List<byte[]> keys(String role, Instant now) {
        return expired(now) ? List.of() : listed(role, now);
    }

    /** Same, ignoring the keyset's own expiry (delegated root signers of the previous keyset). */
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

    private boolean newerThan(Keyset other) {
        return epoch > other.epoch || (epoch == other.epoch && version > other.version);
    }

    /**
     * Verifies {@code raw} against the embedded roots and the previously accepted keyset ({@code null} on first contact).
     * Embedded anchors newer than {@code prev} are verified first, in order (each must be emergency-signed, their own
     * expiry is not checked) and then serve as the previous keyset.
     */
    public static Keyset open(byte[] raw, RootKeys roots, Keyset prev, Instant now) throws KeysetException {
        if (!roots.present()) {
            throw new KeysetException(KeysetException.Reason.BAD_FORMAT, "no root keys in this build");
        }
        JsonObject env = envelopeOf(raw);
        Parsed p = parse(env);
        boolean anchored = false;
        for (JsonObject a : anchorsOf(env)) {
            Parsed ap = parse(a);
            Keyset ak = ap.keyset(false, false);
            if (prev != null && !ak.newerThan(prev)) {
                continue;
            }
            Keyset verified;
            try {
                verified = check(ap, roots, prev, now, false);
            } catch (KeysetException e) {
                throw new KeysetException(e.reason(), "anchor: " + e.getMessage());
            }
            if (!verified.byEmergency) {
                throw bad("an anchor must be signed by an emergency root");
            }
            prev = verified;
            anchored = true;
        }
        Keyset k = check(p, roots, prev, now, true);
        return anchored ? p.keyset(k.byEmergency, true) : k;
    }

    private static Keyset check(Parsed p, RootKeys roots, Keyset prev, Instant now, boolean checkExpiry)
            throws KeysetException {
        Keyset doc = p.keyset(false, false);
        Boolean byEmergency = findSigner(p, doc, roots, prev, now);
        if (byEmergency == null) {
            throw new KeysetException(KeysetException.Reason.BAD_SIGNATURE, "no valid root signature");
        }
        for (byte[] r : roots.all()) {
            if (doc.lists(r)) {
                throw bad("an embedded root key is listed in the keyset");
            }
        }
        if (!doc.hasEmergency(roots, now)) {
            throw bad("no usable emergency root would remain");
        }
        long prevEpoch = 0;
        String prevRoot = "";
        if (prev != null) {
            prevEpoch = prev.epoch;
            prevRoot = prev.rootSection(roots);
            if (prev.newerThan(doc)) {
                throw new KeysetException(KeysetException.Reason.ROLLBACK,
                        "version " + doc.epoch + "." + doc.version + " is older than " + prev.epoch + "." + prev.version);
            }
            if (doc.epoch == prev.epoch && doc.version == prev.version && !Arrays.equals(doc.payload, prev.payload)) {
                throw new KeysetException(KeysetException.Reason.ROLLBACK, "same version, different content");
            }
            for (String r : prev.rootRevocations(roots)) {
                if (!doc.revoked.contains(r)) {
                    throw bad("root revocation dropped");
                }
            }
        }
        if (!byEmergency && (doc.epoch != prevEpoch || !doc.rootSection(roots).equals(prevRoot))) {
            throw new KeysetException(KeysetException.Reason.NEEDS_EMERGENCY,
                    "epoch or root keys changed without an emergency root signature");
        }
        if (checkExpiry && doc.expired(now)) {
            throw new KeysetException(KeysetException.Reason.EXPIRED, "keyset expired " + doc.expiresAt);
        }
        return p.keyset(byEmergency, false);
    }

    /**
     * Reads the cached copy without checking signatures: it was verified by {@link #open} before it was written to the
     * user's own data folder. Used only as the anti-rollback reference and the trusted state at start.
     */
    public static Keyset trustedCache(byte[] raw) throws KeysetException {
        return parse(raw).keyset(false, false);
    }

    /**
     * TRUE: signed by an emergency root (embedded, or a {@code root_emergency} key of prev); FALSE: by a working root
     * (embedded, or a {@code root} key of prev); null: none. A signer revoked by prev or by the document itself never counts.
     */
    private static Boolean findSigner(Parsed p, Keyset doc, RootKeys roots, Keyset prev, Instant now) {
        if (signedByAny(p, doc, candidates(roots.emergency(), ROOT_EMERGENCY, prev, now), prev)) {
            return Boolean.TRUE;
        }
        if (signedByAny(p, doc, candidates(roots.working(), ROOT, prev, now), prev)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static List<byte[]> candidates(List<byte[]> embedded, String delegatedRole, Keyset prev, Instant now) {
        List<byte[]> out = new ArrayList<>(embedded);
        if (prev != null) {
            out.addAll(prev.listed(delegatedRole, now));
        }
        return out;
    }

    private static boolean signedByAny(Parsed p, Keyset doc, List<byte[]> keys, Keyset prev) {
        for (byte[] k : keys) {
            if ((prev == null || !prev.revokes(k)) && !doc.revokes(k) && signedBy(p, k)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasEmergency(RootKeys roots, Instant now) {
        for (byte[] r : roots.emergency()) {
            if (!revokes(r)) {
                return true;
            }
        }
        return !listed(ROOT_EMERGENCY, now).isEmpty();
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

    private static boolean isRootRole(String role) {
        return role.equals(ROOT) || role.equals(ROOT_EMERGENCY);
    }

    private Set<String> rootNames(RootKeys roots) {
        Set<String> names = new HashSet<>();
        for (byte[] r : roots.all()) {
            names.add(Ed25519.keyId(r));
            names.add(Base64.getEncoder().encodeToString(r));
        }
        for (WorkingKey k : keys) {
            if (isRootRole(k.role())) {
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

    /** Everything only an emergency root may change: sorted delegated-root lines, then the sorted root revocations. */
    private String rootSection(RootKeys roots) {
        TreeSet<String> parts = new TreeSet<>();
        for (WorkingKey k : keys) {
            if (isRootRole(k.role())) {
                parts.add("key " + k.role() + " " + k.id() + " " + k.base64() + " " + k.expiresAt());
            }
        }
        List<String> all = new ArrayList<>(parts);
        all.addAll(rootRevocations(roots));
        return String.join("\n", all);
    }

    // ------------------------------------------------------------------ parsing

    private record Parsed(byte[] payload, List<byte[]> sigs, long epoch, long version, Instant expiresAt,
                          List<WorkingKey> keys, Set<String> revoked) {
        Keyset keyset(boolean byEmergency, boolean anchored) {
            return new Keyset(epoch, version, expiresAt, keys, revoked, payload, byEmergency, anchored);
        }
    }

    private static JsonObject envelopeOf(byte[] raw) throws KeysetException {
        if (raw == null || raw.length > MAX_BYTES) {
            throw bad("not a keyset envelope");
        }
        try {
            JsonObject env = GSON.fromJson(new String(raw, StandardCharsets.UTF_8), JsonObject.class);
            if (env == null) {
                throw bad("not a keyset envelope");
            }
            return env;
        } catch (JsonParseException | ClassCastException | IllegalStateException e) {
            throw bad("not a keyset envelope");
        }
    }

    /** The anchor envelopes of {@code env} (oldest first); none is an empty list. */
    private static List<JsonObject> anchorsOf(JsonObject env) throws KeysetException {
        List<JsonObject> out = new ArrayList<>();
        if (!env.has("anchors") || env.get("anchors").isJsonNull()) {
            return out;
        }
        try {
            for (JsonElement a : env.getAsJsonArray("anchors")) {
                JsonObject o = a.getAsJsonObject();
                if (o.has("anchors") && !o.get("anchors").isJsonNull() && !o.getAsJsonArray("anchors").isEmpty()) {
                    throw bad("nested anchors");
                }
                out.add(o);
            }
        } catch (ClassCastException | IllegalStateException e) {
            throw bad("anchors");
        }
        if (out.size() > MAX_ANCHORS) {
            throw bad("too many anchors");
        }
        return out;
    }

    private static Parsed parse(byte[] raw) throws KeysetException {
        return parse(envelopeOf(raw));
    }

    private static Parsed parse(JsonObject env) throws KeysetException {
        try {
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
