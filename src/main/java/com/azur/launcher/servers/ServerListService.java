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

    /**
     * The key the app trusts: the embedded one. A local {@code servers_pubkey.txt} counts only in a dev run
     * ({@code -Dazur.home}), so a leftover dev key can never override the release key.
     */
    public static PublicKey appKey(Path override) throws IOException, GeneralSecurityException {
        boolean dev = !System.getProperty(com.azur.launcher.config.AppDirs.HOME_PROPERTY, "").isBlank();
        return loadKey(dev ? override : null);
    }

    /** Network first, then the cache. Throws (with a short reason) when neither gives a verifiable list. */
    public Loaded load() throws IOException {
        String networkReason = "no URL configured";
        if (url != null && !url.isBlank()) {
            long start = clock.millis();
            log.accept("Server list: fetching " + url);
            try {
                byte[] bytes = fetcher.fetch(url);
                ServerList list = ServerListVerifier.open(bytes, key, clock.instant());
                log.accept("Server list: network OK, " + bytes.length + " bytes in " + (clock.millis() - start)
                        + " ms, signature OK, valid until " + list.validUntil() + ", " + list.servers().size() + " server(s)");
                saveCache(bytes);
                return new Loaded(list, Source.NETWORK);
            } catch (IOException | ServerListException e) {
                networkReason = reasonOf(e);
                log.accept("Server list: direct connection failed: " + networkReason + " (" + (clock.millis() - start)
                        + " ms), proxy not configured (Settings > Advanced); trying the cache");
            }
        }
        try {
            Loaded cached = fromCache();
            log.accept("Server list: from cache (" + cached.source() + "), valid until " + cached.list().validUntil()
                    + (networkReason.isEmpty() ? "" : "; server unavailable: " + networkReason));
            return cached;
        } catch (IOException e) {
            log.accept("Server list: no usable list: network: " + networkReason + "; cache: " + e.getMessage());
            throw new IOException("network: " + networkReason + "; cache: " + e.getMessage(), e);
        }
    }

    /** The verified cached copy only (no network), or {@code null}; used to show the list at once while the network is slow. */
    public Loaded cached() {
        try {
            return fromCache();
        } catch (IOException e) {
            log.accept("Server list: cache not usable: " + e.getMessage());
            return null;
        }
    }

    /** A short human reason for a failed fetch or verification: timeout, DNS, TLS, host policy, HTTP code, signature, expiry. */
    public static String reasonOf(Throwable e) {
        if (e instanceof ServerListException sle) {
            return switch (sle.reason()) {
                case BAD_SIGNATURE -> "signature check failed";
                case EXPIRED -> "list expired (" + sle.getMessage() + ")";
                case BAD_FORMAT -> "invalid list (" + sle.getMessage() + ")";
            };
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.SocketTimeoutException) {
                return "timeout";
            }
            if (t instanceof java.net.UnknownHostException) {
                return "DNS: host not found";
            }
            if (t instanceof javax.net.ssl.SSLException) {
                return "TLS error (" + t.getMessage() + ")";
            }
            if (t.getClass().getSimpleName().equals("BlockedHostException")) {
                return "host not allowed by policy";
            }
        }
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return m.startsWith("HTTP ") ? m.replaceAll(" -> .*", "") : m;
    }

    private Loaded fromCache() throws IOException {
        if (!Files.isRegularFile(cacheFile)) {
            throw new IOException("no cached list");
        }
        byte[] bytes = Files.readAllBytes(cacheFile);
        try {
            return new Loaded(ServerListVerifier.open(bytes, key, clock.instant()), Source.CACHE);
        } catch (ServerListException e) {
            if (e.reason() != ServerListException.Reason.EXPIRED) {
                throw new IOException("cached list rejected: " + reasonOf(e), e);
            }
            try {
                return new Loaded(ServerListVerifier.openIgnoringExpiry(bytes, key, clock.instant()),
                        Source.CACHE_STALE);
            } catch (ServerListException inner) {
                throw new IOException("cached list rejected: " + reasonOf(inner), inner);
            }
        }
    }

    /** The cache is only a fallback: a failed write is logged and never replaces the list just loaded. */
    private void saveCache(byte[] bytes) {
        try {
            writeCache(bytes);
        } catch (IOException e) {
            log.accept("Server list cache not saved (" + e + "); using the downloaded list from memory");
        }
    }

    private synchronized void writeCache(byte[] bytes) throws IOException {
        Files.createDirectories(cacheFile.getParent());
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            Path tmp = Files.createTempFile(cacheFile.getParent(), cacheFile.getFileName().toString(), ".tmp");
            try {
                Files.write(tmp, bytes);
                try {
                    Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, cacheFile, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (IOException e) {
                last = e;
                Files.deleteIfExists(tmp);
            }
        }
        throw last;
    }
}
