package com.azur.launcher.servers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.time.Clock;
import java.util.function.Consumer;

/**
 * Loads the signed list from the network and keeps the last good copy on disk. When the network (or the
 * downloaded file) fails, the cached copy is used; a cached copy that has only expired is still shown, marked stale.
 * Blocking: call from a background thread.
 */
public final class ServerListService {

    public enum Source { NETWORK, CACHE, CACHE_STALE }

    public record Loaded(ServerList list, Source source) {
    }

    /** Fetches bytes from a URL (HttpUtils in the app, a fake in tests). */
    @FunctionalInterface
    public interface Fetcher {
        byte[] fetch(String url) throws IOException;
    }

    private static final String PUBLIC_KEY_RESOURCE = "/servers_pubkey.txt";

    private final Path cacheFile;
    private final PublicKey key;
    private final String url;
    private final Fetcher fetcher;
    private final Clock clock;
    private final Consumer<String> log;

    public ServerListService(Path cacheFile, PublicKey key, String url, Fetcher fetcher, Clock clock,
                             Consumer<String> log) {
        this.cacheFile = cacheFile;
        this.key = key;
        this.url = url;
        this.fetcher = fetcher;
        this.clock = clock;
        this.log = log;
    }

    /** The embedded key, or the key in {@code override} (a file with the base64 key) when that file exists. */
    public static PublicKey loadKey(Path override) throws IOException, GeneralSecurityException {
        if (override != null && Files.isRegularFile(override)) {
            return ServerListVerifier.publicKey(Files.readString(override, StandardCharsets.UTF_8));
        }
        try (InputStream in = ServerListService.class.getResourceAsStream(PUBLIC_KEY_RESOURCE)) {
            if (in == null) {
                throw new IOException("Embedded public key is missing");
            }
            return ServerListVerifier.publicKey(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /** Network first, then the cache. Throws when neither gives a verifiable list. */
    public Loaded load() throws IOException {
        if (url != null && !url.isBlank()) {
            try {
                byte[] bytes = fetcher.fetch(url);
                ServerList list = ServerListVerifier.open(bytes, key, clock.instant());
                writeCache(bytes);
                return new Loaded(list, Source.NETWORK);
            } catch (IOException | ServerListException e) {
                log.accept("Server list unavailable (" + e.getMessage() + "), using the cache");
            }
        }
        return fromCache();
    }

    private Loaded fromCache() throws IOException {
        if (!Files.isRegularFile(cacheFile)) {
            throw new IOException("No server list: network failed and there is no cache");
        }
        byte[] bytes = Files.readAllBytes(cacheFile);
        try {
            return new Loaded(ServerListVerifier.open(bytes, key, clock.instant()), Source.CACHE);
        } catch (ServerListException e) {
            if (e.reason() != ServerListException.Reason.EXPIRED) {
                throw new IOException("Cached server list rejected: " + e.getMessage(), e);
            }
            try {
                return new Loaded(ServerListVerifier.openIgnoringExpiry(bytes, key, clock.instant()),
                        Source.CACHE_STALE);
            } catch (ServerListException inner) {
                throw new IOException("Cached server list rejected: " + inner.getMessage(), inner);
            }
        }
    }

    private void writeCache(byte[] bytes) throws IOException {
        Files.createDirectories(cacheFile.getParent());
        Path tmp = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING);
    }
}
