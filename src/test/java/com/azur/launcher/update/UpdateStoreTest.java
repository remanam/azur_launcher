package com.azur.launcher.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UpdateStoreTest {

    @TempDir
    Path dir;
    AtomicLong now = new AtomicLong(1_000_000);
    UpdateStore store;

    @BeforeEach
    void setUp() {
        store = UpdateStore.at(dir, now::get, 60_000);
    }

    private void stage(String version, String content) throws Exception {
        Path tmp = Files.createTempFile(dir, "dl", ".jar");
        Files.writeString(tmp, content);
        store.stagePending(tmp, version, UpdateTestSupport.sha256(content.getBytes(StandardCharsets.UTF_8)));
    }

    private String current() throws Exception {
        return Files.readString(dir.resolve("current.jar"));
    }

    @Test
    void embeddedRunsWhenNothingStaged() {
        assertEquals(Optional.empty(), store.select("1.0.0"));
    }

    @Test
    void pendingIsAppliedAndConfirmedKept() throws Exception {
        stage("1.1.0", "v110");
        assertEquals(Optional.of(dir.resolve("current.jar")), store.select("1.0.0"));
        assertEquals("v110", current());
        store.confirmStarted("1.1.0");
        now.addAndGet(10 * 60_000);
        assertTrue(store.select("1.0.0").isPresent());
        assertFalse(store.isRejected("1.1.0"));
    }

    @Test
    void unconfirmedStartIsRolledBackToThePreviousJarAfterTheGrace() throws Exception {
        stage("1.1.0", "v110");
        store.select("1.0.0");
        store.confirmStarted("1.1.0");
        stage("1.2.0", "v120");
        store.select("1.0.0");
        assertEquals("v120", current());
        now.addAndGet(30_000);
        assertEquals("v120", Files.readString(store.select("1.0.0").orElseThrow()), "inside the grace the new jar stays");
        now.addAndGet(31_000);
        assertEquals("v110", Files.readString(store.select("1.0.0").orElseThrow()));
        assertTrue(store.isRejected("1.2.0"));
        assertNull(store.pendingVersion());
    }

    @Test
    void rollbackWithoutPreviousFallsBackToEmbedded() throws Exception {
        stage("1.1.0", "v110");
        store.select("1.0.0");
        now.addAndGet(120_000);
        assertEquals(Optional.empty(), store.select("1.0.0"));
        assertTrue(store.isRejected("1.1.0"));
        stage("1.1.0", "v110");
        assertEquals(Optional.empty(), store.select("1.0.0"), "a rejected version is never applied again");
    }

    @Test
    void rollbackNowRestoresPrevious() throws Exception {
        stage("1.1.0", "v110");
        store.select("1.0.0");
        store.confirmStarted("1.1.0");
        stage("1.2.0", "v120");
        store.select("1.0.0");
        store.rollbackNow();
        assertEquals("v110", current());
        assertTrue(store.isRejected("1.2.0"));
    }

    @Test
    void damagedOrOutdatedCurrentIsDropped() throws Exception {
        stage("1.1.0", "v110");
        store.select("1.0.0");
        store.confirmStarted("1.1.0");
        Files.writeString(dir.resolve("current.jar"), "tampered");
        assertEquals(Optional.empty(), store.select("1.0.0"));
        stage("1.1.0", "v110");
        store.select("1.0.0");
        store.confirmStarted("1.1.0");
        assertEquals(Optional.empty(), store.select("1.1.0"), "a new installer with the same version wins");
    }

    @Test
    void pendingWithWrongHashIsDiscarded() throws Exception {
        stage("1.1.0", "v110");
        Files.writeString(dir.resolve("pending.jar"), "swapped");
        assertEquals(Optional.empty(), store.select("1.0.0"));
        assertNull(store.pendingVersion());
    }

    @Test
    void jarThatReportsTheWrongVersionIsNotConfirmedAndRolledBack() throws Exception {
        // 1.0.8..1.0.10 once reported AppVersion 1.0.7: confirmStarted never matched, the update was rejected later
        stage("1.0.10", "v1010");
        store.select("1.0.7");
        store.confirmStarted("1.0.7");
        now.addAndGet(61_000);
        assertEquals(Optional.empty(), store.select("1.0.7"));
        assertTrue(store.isRejected("1.0.10"));
    }

    @Test
    void jarThatReportsItsOwnVersionStaysAfterTheGrace() throws Exception {
        stage("1.0.10", "v1010");
        store.select("1.0.7");
        store.confirmStarted("1.0.10");
        now.addAndGet(61_000);
        assertEquals("v1010", Files.readString(store.select("1.0.7").orElseThrow()));
    }
}
