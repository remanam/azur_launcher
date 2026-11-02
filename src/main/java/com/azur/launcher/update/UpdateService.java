package com.azur.launcher.update;

import com.azur.launcher.net.Hashes;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Launcher self-update, stage 1: reads the signed manifest, and for a newer version downloads the jar, checks size,
 * sha256 and the manifest signature and stages it in {@link UpdateStore}. It is applied by the bootstrap at the next
 * start ({@link com.azur.launcher.Launcher}). Nothing is downloaded or applied while the game runs or prepares.
 */
public final class UpdateService {

    public enum Kind { UP_TO_DATE, READY, BUSY, WRAPPER_OLD, FAILED }

    /** {@code manifest} is null for BUSY and FAILED. */
    public record Status(Kind kind, UpdateManifest manifest, String message) {
    }

    /** Network access (HttpUtils in the app, a fake in tests). */
    public interface Transport {
        byte[] get(String url) throws IOException;

        void download(String url, Path target, String sha256, long size) throws IOException;
    }

    private final UpdateStore store;
    private final PublicKey key;
    private final String manifestUrl;
    private final Transport transport;
    private final String runningVersion;
    private final String wrapperVersion;
    private final BooleanSupplier gameBusy;
    private final Consumer<String> log;

    public UpdateService(UpdateStore store, PublicKey key, String manifestUrl, Transport transport,
                         String runningVersion, String wrapperVersion, BooleanSupplier gameBusy, Consumer<String> log) {
        this.store = store;
        this.key = key;
        this.manifestUrl = manifestUrl;
        this.transport = transport;
        this.runningVersion = runningVersion;
        this.wrapperVersion = wrapperVersion;
        this.gameBusy = gameBusy;
        this.log = log;
    }

    /** One check (blocking; call from a background thread). Never throws. */
    public Status check() {
        if (gameBusy.getAsBoolean()) {
            return new Status(Kind.BUSY, null, "game is running or preparing");
        }
        try {
            UpdateManifest manifest = UpdateManifest.open(transport.get(manifestUrl), key);
            if (!LauncherVersions.newer(manifest.version(), runningVersion) || store.isRejected(manifest.version())) {
                return new Status(Kind.UP_TO_DATE, manifest, "");
            }
            if (LauncherVersions.newer(manifest.minWrapper(), wrapperVersion)) {
                log.accept("Launcher update " + manifest.version() + " needs wrapper " + manifest.minWrapper()
                        + " (installed " + wrapperVersion + "): download the new installer from the site");
                return new Status(Kind.WRAPPER_OLD, manifest, "");
            }
            if (!manifest.version().equals(store.pendingVersion())) {
                fetch(manifest);
            }
            return new Status(Kind.READY, manifest, "");
        } catch (UpdateException | IOException | RuntimeException e) {
            log.accept("Launcher update check failed: " + e.getMessage());
            return new Status(Kind.FAILED, null, String.valueOf(e.getMessage()));
        }
    }

    /** True when restarting into the staged update is allowed (no game running). */
    public boolean mayRestart() {
        return !gameBusy.getAsBoolean() && store.pendingVersion() != null;
    }

    private void fetch(UpdateManifest manifest) throws IOException, UpdateException {
        if (!URI.create(manifest.url()).getHost().equalsIgnoreCase(URI.create(manifestUrl).getHost())) {
            throw new UpdateException("jar is not on the manifest host");
        }
        Files.createDirectories(store.dir());
        Path part = store.dir().resolve("download.jar");
        try {
            log.accept("Launcher update " + manifest.version() + ": downloading " + manifest.size() + " bytes");
            transport.download(manifest.url(), part, manifest.sha256(), manifest.size());
            if (Files.size(part) != manifest.size() || !manifest.sha256().equals(Hashes.sha256(part))) {
                throw new UpdateException("downloaded jar does not match the signed size/sha256");
            }
            if (gameBusy.getAsBoolean()) {
                throw new UpdateException("game started during the download");
            }
            store.stagePending(part, manifest.version(), manifest.sha256());
            log.accept("Launcher update " + manifest.version() + " staged, applied at the next start");
        } finally {
            Files.deleteIfExists(part);
        }
    }
}
