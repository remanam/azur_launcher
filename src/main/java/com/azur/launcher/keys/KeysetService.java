package com.azur.launcher.keys;

import com.azur.launcher.update.LauncherVersions;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.PublicKey;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Keeps the accepted keyset: the verified copy {@code <data>/keyset.json} (outside the deletable cache folder: its
 * presence also switches the legacy path off for good), a refresh from {@code <site>/dl/keyset.json} at the first use
 * after start and then at most every {@link #MAX_AGE} (feed, update and Play call {@link #refreshIfStale}), so a
 * revocation reaches running launchers within minutes. A refused or unreachable keyset keeps the previous state.
 */
public final class KeysetService {

    /** Below this launcher version a jar with root keys may still use the old single key until a keyset arrives. */
    public static final String LEGACY_CUTOVER = "1.1.0";
    public static final Duration MAX_AGE = Duration.ofMinutes(10);

    /** Fetches bytes (HttpUtils in the app, a fake in tests). */
    @FunctionalInterface
    public interface Fetcher {
        byte[] fetch(String url) throws IOException;
    }

    private final Path file;
    private final RootKeys roots;
    private final PublicKey legacyKey;
    private final String url;
    private final Fetcher fetcher;
    private final Clock clock;
    private final Consumer<String> log;
    private final String appVersion;
    private Keyset accepted;
    private boolean seen;
    private Instant lastRefresh;

    public KeysetService(Path file, RootKeys roots, PublicKey legacyKey, String url, Fetcher fetcher, Clock clock,
                         Consumer<String> log, String appVersion) {
        this.file = file;
        this.roots = roots;
        this.legacyKey = legacyKey;
        this.url = url == null ? "" : url;
        this.fetcher = fetcher;
        this.clock = clock;
        this.log = log;
        this.appVersion = appVersion;
        loadCache();
    }

    private void loadCache() {
        if (!roots.present() || !Files.exists(file)) {
            return;
        }
        seen = true; // even a damaged copy keeps the legacy path off (deleting files must not downgrade trust)
        try {
            accepted = Keyset.trustedCache(Files.readAllBytes(file));
        } catch (IOException | KeysetException e) {
            log.accept("Keyset cache unusable: " + e.getMessage());
        }
    }

    /** The current trust without network access. */
    public synchronized KeyTrust trust() {
        Instant now = clock.instant();
        if (!roots.present()) {
            return new KeyTrust(KeyTrust.Mode.LEGACY, null, legacyKey, "no root keys in this build");
        }
        if (accepted != null && !accepted.expired(now)) {
            return new KeyTrust(KeyTrust.Mode.KEYSET, accepted, legacyKey, "");
        }
        if (seen) {
            return new KeyTrust(KeyTrust.Mode.LOCKED, accepted, legacyKey,
                    accepted == null ? "cached keyset unusable" : "keyset expired " + accepted.expiresAt());
        }
        if (LauncherVersions.newer(LEGACY_CUTOVER, appVersion)) {
            return new KeyTrust(KeyTrust.Mode.TRANSITION, null, legacyKey, "no keyset yet");
        }
        return new KeyTrust(KeyTrust.Mode.LOCKED, null, legacyKey, "no keyset and the legacy key is retired");
    }

    /** Refreshes when the last attempt is older than {@code maxAge} (or never happened). Blocking; background threads. */
    public KeyTrust refreshIfStale(Duration maxAge) {
        synchronized (this) {
            if (lastRefresh != null && clock.instant().isBefore(lastRefresh.plus(maxAge))) {
                return trust();
            }
        }
        return refresh();
    }

    /** One fetch now. Never throws; failures are logged and keep the previous keyset. */
    public KeyTrust refresh() {
        if (!roots.present() || url.isBlank()) {
            return trust();
        }
        synchronized (this) {
            lastRefresh = clock.instant();
        }
        byte[] bytes;
        try {
            bytes = fetcher.fetch(url);
        } catch (IOException e) {
            log.accept("Keyset: not fetched (" + e.getMessage() + "), keeping the current one");
            return trust();
        }
        synchronized (this) {
            try {
                Keyset fresh = Keyset.open(bytes, roots, accepted, clock.instant());
                boolean changed = accepted == null || fresh.epoch() != accepted.epoch() || fresh.version() != accepted.version();
                accepted = fresh;
                seen = true;
                if (changed) {
                    log.accept("Keyset: accepted epoch " + fresh.epoch() + " version " + fresh.version() + ", expires "
                            + fresh.expiresAt() + (fresh.byEmergency() ? " (emergency root)" : "") + (fresh.anchored() ? " (via anchors)" : ""));
                    write(bytes);
                }
            } catch (KeysetException e) {
                log.accept("Keyset refused (" + e.reason() + "): " + e.getMessage());
            } catch (IOException e) {
                log.accept("Keyset: verified but not saved (" + e.getMessage() + "), used from memory");
            }
            return trust();
        }
    }

    private void write(byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = Files.createTempFile(file.getParent(), "keyset", ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
