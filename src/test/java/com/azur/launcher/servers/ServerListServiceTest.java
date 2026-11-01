package com.azur.launcher.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerListServiceTest {

    @TempDir
    Path dir;

    private final KeyPair key = key();

    private static KeyPair key() {
        try {
            return ServerListVerifierTest.newKey();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ServerListService service(ServerListService.Fetcher fetcher, String now) {
        return new ServerListService(dir.resolve("cache/servers.json"), key.getPublic(), "https://x.example/servers.json",
                fetcher, Clock.fixed(Instant.parse(now), ZoneOffset.UTC), line -> { });
    }

    private byte[] good() throws Exception {
        return ServerListVerifierTest.envelope(ServerListVerifierTest.PAYLOAD, key);
    }

    @Test
    void unwritableCacheDoesNotBlockTheDownloadedList() throws Exception {
        Files.writeString(dir.resolve("cache"), "a file, not a directory");
        var loaded = service(url -> good(), "2026-10-29T00:00:00Z").load();
        assertEquals(ServerListService.Source.NETWORK, loaded.source());
        assertEquals(2, loaded.list().servers().size());
    }

    @Test
    void concurrentLoadsBothSucceedAndLeaveNoTempFiles() throws Exception {
        byte[] file = good();
        var a = java.util.concurrent.CompletableFuture.supplyAsync(() -> load(file));
        var b = java.util.concurrent.CompletableFuture.supplyAsync(() -> load(file));
        assertEquals(ServerListService.Source.NETWORK, a.get().source());
        assertEquals(ServerListService.Source.NETWORK, b.get().source());
        try (var files = Files.list(dir.resolve("cache"))) {
            assertEquals(1, files.count());
        }
    }

    private ServerListService.Loaded load(byte[] file) {
        try {
            return service(url -> file, "2026-10-29T00:00:00Z").load();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void networkListIsCachedAndUsedWhenOffline() throws Exception {
        byte[] file = good();
        var online = service(url -> file, "2026-10-29T00:00:00Z").load();
        assertEquals(ServerListService.Source.NETWORK, online.source());
        var offline = service(url -> {
            throw new IOException("no network");
        }, "2026-10-29T00:00:00Z").load();
        assertEquals(ServerListService.Source.CACHE, offline.source());
        assertEquals(2, offline.list().servers().size());
    }

    @Test
    void badSignatureFromTheNetworkFallsBackToTheCacheAndIsNotCached() throws Exception {
        byte[] file = good();
        service(url -> file, "2026-10-29T00:00:00Z").load();
        byte[] forged = ServerListVerifierTest.envelope(ServerListVerifierTest.PAYLOAD.replace("a.example", "evil"),
                ServerListVerifierTest.newKey());
        var loaded = service(url -> forged, "2026-10-29T00:00:00Z").load();
        assertEquals(ServerListService.Source.CACHE, loaded.source());
        assertEquals("a.example", loaded.list().servers().get(0).address());
        assertTrue(Files.readString(dir.resolve("cache/servers.json")).equals(new String(file, StandardCharsets.UTF_8)));
    }

    @Test
    void expiredCacheIsShownAsStale() throws Exception {
        byte[] file = good();
        service(url -> file, "2026-10-29T00:00:00Z").load();
        var stale = service(url -> {
            throw new IOException("offline");
        }, "2028-01-01T00:00:00Z").load();
        assertEquals(ServerListService.Source.CACHE_STALE, stale.source());
    }

    @Test
    void expiredListFromTheNetworkIsNotCached() throws Exception {
        byte[] file = good();
        assertThrows(IOException.class, () -> service(url -> file, "2028-01-01T00:00:00Z").load());
        assertTrue(Files.notExists(dir.resolve("cache/servers.json")));
    }

    @Test
    void tamperedCacheIsRejected() throws Exception {
        byte[] file = good();
        service(url -> file, "2026-10-29T00:00:00Z").load();
        Path cache = dir.resolve("cache/servers.json");
        Files.writeString(cache, Files.readString(cache).replace("\"sig\":\"", "\"sig\":\"AAAA"));
        assertThrows(IOException.class, () -> service(url -> {
            throw new IOException("offline");
        }, "2026-10-29T00:00:00Z").load());
    }

    @Test
    void noNetworkAndNoCacheFails() {
        assertThrows(IOException.class, () -> service(url -> {
            throw new IOException("offline");
        }, "2026-10-29T00:00:00Z").load());
    }

    /** The debug-build path: empty URL, list in cache/servers.json, key from servers_pubkey.txt. */
    @Test
    void emptyUrlReadsCacheFileSignedWithTheKeyOverride() throws Exception {
        Files.createDirectories(dir.resolve("cache"));
        Files.write(dir.resolve("cache/servers.json"), good());
        Path keyFile = dir.resolve("servers_pubkey.txt");
        Files.writeString(keyFile, java.util.Base64.getEncoder().encodeToString(
                java.util.Arrays.copyOfRange(key.getPublic().getEncoded(), 12, 44)), StandardCharsets.UTF_8);
        var service = new ServerListService(dir.resolve("cache/servers.json"), ServerListService.loadKey(keyFile), "",
                url -> {
                    throw new IOException("must not be called");
                }, Clock.fixed(Instant.parse("2026-10-29T00:00:00Z"), ZoneOffset.UTC), line -> { });
        assertEquals(2, service.load().list().servers().size());
    }
}
