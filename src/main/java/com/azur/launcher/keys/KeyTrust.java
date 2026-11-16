package com.azur.launcher.keys;

import java.security.PublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Which keys the launcher trusts right now (immutable snapshot of {@link KeysetService}).
 * <ul>
 *   <li>{@link Mode#KEYSET}: a verified, unexpired keyset; servers.json/feed by its {@code servers} keys, the pack
 *       manifest by its {@code pack} keys (the key in servers.json is ignored), launcher.json by a {@code launcher} key.</li>
 *   <li>{@link Mode#TRANSITION}: the jar has root keys but no keyset was ever accepted and this version is below
 *       {@link KeysetService#LEGACY_CUTOVER}: the old single embedded key for servers.json/feed, the pack key from
 *       servers.json; launcher self-update is refused until a keyset arrives.</li>
 *   <li>{@link Mode#LEGACY}: a jar without root keys (dev build): the old single-key path for everything.</li>
 *   <li>{@link Mode#LOCKED}: roots present but no usable keyset (the cached one expired, or the cutover passed):
 *       nothing verifies until a fresh keyset is fetched.</li>
 * </ul>
 */
public record KeyTrust(Mode mode, Keyset keyset, PublicKey legacyKey, String reason) {

    public enum Mode { KEYSET, TRANSITION, LEGACY, LOCKED }

    /** Keys for servers.json and feed.json. */
    public List<PublicKey> serverKeys(Instant now) {
        return switch (mode) {
            case KEYSET -> Ed25519.publicKeys(keyset.keys(Keyset.SERVERS, now));
            case TRANSITION, LEGACY -> Objects.isNull(legacyKey) ? List.of() : List.of(legacyKey);
            case LOCKED -> List.of();
        };
    }

    /** Pack manifest keys; empty = take the key of the servers.json entry (legacy and transition only). */
    public Optional<List<byte[]>> packKeys(Instant now) {
        return switch (mode) {
            case KEYSET -> Optional.of(keyset.keys(Keyset.PACK, now));
            case TRANSITION, LEGACY -> Optional.empty();
            case LOCKED -> Optional.of(List.of());
        };
    }

    /** Keys whose signature in launcher.json {@code signatures} is accepted (KEYSET mode only). */
    public List<PublicKey> launcherKeys(Instant now) {
        return mode == Mode.KEYSET ? Ed25519.publicKeys(keyset.keys(Keyset.LAUNCHER, now)) : List.of();
    }

    /** Only a jar without root keys accepts the old single signature of launcher.json. */
    public boolean legacyLauncherSignature() {
        return mode == Mode.LEGACY;
    }
}
