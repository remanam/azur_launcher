package com.azur.launcher.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateServiceTest {

    @TempDir
    Path dir;
    KeyPair key;
    byte[] jar = "new-launcher".getBytes(StandardCharsets.UTF_8);
    byte[] servedJar = jar;
    byte[] manifestBytes;
    AtomicInteger calls = new AtomicInteger();
    AtomicBoolean busy = new AtomicBoolean();
    UpdateStore store;

    @BeforeEach
    void setUp() throws Exception {
        key = UpdateTestSupport.newKey();
        store = UpdateStore.at(dir, System::currentTimeMillis, 60_000);
        manifestBytes = UpdateTestSupport.signed(UpdateTestSupport.manifest("1.1.0", jar, "1.0.0", false), key);
    }

    private UpdateService service(String running, String wrapper) {
        UpdateService.Transport transport = new UpdateService.Transport() {
            @Override
            public byte[] get(String url) {
                calls.incrementAndGet();
                return manifestBytes;
            }

            @Override
            public void download(String url, Path target, String sha256, long size) throws IOException {
                calls.incrementAndGet();
                Files.write(target, servedJar);
            }
        };
        return new UpdateService(store, key.getPublic(), "https://example.test/dl/launcher.json", transport,
                running, wrapper, busy::get, line -> { });
    }

    @Test
    void newerVersionIsDownloadedAndStaged() {
        UpdateService.Status status = service("1.0.0", "1.0.0").check();
        assertEquals(UpdateService.Kind.READY, status.kind());
        assertEquals("1.1.0", store.pendingVersion());
        assertTrue(service("1.0.0", "1.0.0").mayRestart());
    }

    @Test
    void substitutedJarIsNotStaged() {
        servedJar = "evil".getBytes(StandardCharsets.UTF_8);
        assertEquals(UpdateService.Kind.FAILED, service("1.0.0", "1.0.0").check().kind());
        assertNull(store.pendingVersion());
        assertFalse(Files.exists(dir.resolve("download.jar")));
    }

    @Test
    void tamperedManifestDownloadsNothing() {
        manifestBytes = new String(manifestBytes, StandardCharsets.UTF_8).replace("1.1.0", "9.9.9")
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(UpdateService.Kind.FAILED, service("1.0.0", "1.0.0").check().kind());
        assertEquals(1, calls.get(), "only the manifest was requested");
    }

    @Test
    void nothingHappensWhileTheGameRuns() {
        busy.set(true);
        assertEquals(UpdateService.Kind.BUSY, service("1.0.0", "1.0.0").check().kind());
        assertEquals(0, calls.get(), "no network at all");
        assertNull(store.pendingVersion());
        busy.set(false);
        service("1.0.0", "1.0.0").check();
        busy.set(true);
        assertFalse(service("1.0.0", "1.0.0").mayRestart(), "no restart during a game");
    }

    @Test
    void sameOrOlderVersionIsUpToDate() {
        assertEquals(UpdateService.Kind.UP_TO_DATE, service("1.1.0", "1.0.0").check().kind());
        assertEquals(UpdateService.Kind.UP_TO_DATE, service("2.0.0", "1.0.0").check().kind());
        assertEquals(1, calls.get() / 2);
    }

    @Test
    void oldWrapperAsksForTheInstallerAndAppliesNothing() throws Exception {
        manifestBytes = UpdateTestSupport.signed(UpdateTestSupport.manifest("1.1.0", jar, "2.0.0", true), key);
        UpdateService.Status status = service("1.0.0", "1.0.0").check();
        assertEquals(UpdateService.Kind.WRAPPER_OLD, status.kind());
        assertEquals(1, calls.get());
        assertNull(store.pendingVersion());
    }

    @Test
    void rejectedVersionIsNotFetchedAgain() throws Exception {
        service("1.0.0", "1.0.0").check();
        store.select("1.0.0");
        store.rollbackNow();
        calls.set(0);
        assertEquals(UpdateService.Kind.UP_TO_DATE, service("1.0.0", "1.0.0").check().kind());
        assertEquals(1, calls.get());
    }
}
