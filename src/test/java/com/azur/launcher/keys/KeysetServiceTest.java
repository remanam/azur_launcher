package com.azur.launcher.keys;

import static com.azur.launcher.keys.KeysetTestSupport.NOW;
import static com.azur.launcher.keys.KeysetTestSupport.doc;
import static com.azur.launcher.keys.KeysetTestSupport.envelope;
import static com.azur.launcher.keys.KeysetTestSupport.newKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KeysetServiceTest {

    @TempDir
    Path dir;
    final KeysetTestSupport ks = new KeysetTestSupport();
    final KeyPair legacy = newKey();
    final KeyPair servers = newKey();
    byte[] served;
    final AtomicInteger fetches = new AtomicInteger();
    Instant now = NOW;

    private KeysetService service(RootKeys roots, String appVersion) {
        Clock clock = new Clock() {
            @Override
            public ZoneOffset getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(java.time.ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        return new KeysetService(dir.resolve("keyset.json"), roots, legacy.getPublic(), "https://example.test/dl/keyset.json",
                url -> {
                    fetches.incrementAndGet();
                    if (served == null) {
                        throw new IOException("offline");
                    }
                    return served;
                }, clock, line -> { }, appVersion);
    }

    @Test
    void buildWithoutRootsStaysLegacy() {
        KeyTrust t = service(RootKeys.NONE, "1.0.7").refresh();
        assertEquals(KeyTrust.Mode.LEGACY, t.mode());
        assertEquals(legacy.getPublic(), t.serverKeys(now).get(0));
        assertTrue(t.legacyLauncherSignature());
        assertEquals(0, fetches.get(), "no keyset request without roots");
    }

    @Test
    void transitionUntilTheFirstKeysetThenKeysetForGood() {
        KeysetService s = service(ks.roots, "1.0.7");
        KeyTrust t = s.refresh(); // offline
        assertEquals(KeyTrust.Mode.TRANSITION, t.mode());
        assertEquals(legacy.getPublic(), t.serverKeys(now).get(0));
        assertTrue(t.packKeys(now).isEmpty(), "pack key from servers.json in transition");
        assertTrue(t.launcherKeys(now).isEmpty() && !t.legacyLauncherSignature(), "no self-update in transition");

        served = envelope(doc().key("s1", Keyset.SERVERS, servers).key("p1", Keyset.PACK, newKey()), ks.primary);
        t = s.refresh();
        assertEquals(KeyTrust.Mode.KEYSET, t.mode());
        assertEquals(1, t.serverKeys(now).size());
        assertTrue(!t.serverKeys(now).contains(legacy.getPublic()), "legacy key is no longer trusted");
        assertEquals(1, t.packKeys(now).orElseThrow().size());
        assertTrue(Files.exists(dir.resolve("keyset.json")));

        // a new start, offline: the cached keyset is used, never the legacy key
        served = null;
        assertEquals(KeyTrust.Mode.KEYSET, service(ks.roots, "1.0.7").refresh().mode());
    }

    @Test
    void rollbackFromTheNetworkKeepsTheNewerKeyset() {
        KeysetService s = service(ks.roots, "1.0.7");
        served = envelope(doc().version(3).key("s1", Keyset.SERVERS, servers), ks.primary);
        s.refresh();
        served = envelope(doc().version(2).key("s0", Keyset.SERVERS, newKey()), ks.primary);
        KeyTrust t = s.refresh();
        assertEquals(3, t.keyset().version());
        // also across restarts (the cached copy is the reference)
        assertEquals(3, service(ks.roots, "1.0.7").refresh().keyset().version());
    }

    @Test
    void expiredCachedKeysetLocksUntilAFreshOne() {
        KeysetService s = service(ks.roots, "1.0.7");
        served = envelope(doc().expires(NOW.plusSeconds(3600)).key("s1", Keyset.SERVERS, servers), ks.primary);
        s.refresh();
        now = NOW.plusSeconds(7200);
        served = null;
        KeyTrust t = s.refresh();
        assertEquals(KeyTrust.Mode.LOCKED, t.mode(), "no fallback to the legacy key after a keyset was seen");
        assertTrue(t.serverKeys(now).isEmpty());
        assertTrue(t.packKeys(now).orElseThrow().isEmpty());
        served = envelope(doc().version(2).expires(now.plusSeconds(86400)).key("s1", Keyset.SERVERS, servers), ks.primary);
        assertEquals(KeyTrust.Mode.KEYSET, s.refresh().mode());
    }

    @Test
    void afterTheCutoverTheLegacyKeyIsRetired() {
        assertEquals(KeyTrust.Mode.LOCKED, service(ks.roots, KeysetService.LEGACY_CUTOVER).refresh().mode());
    }

    @Test
    void revocationIsPickedUpByTheThrottledRefresh() {
        KeysetService s = service(ks.roots, "1.0.7");
        served = envelope(doc().key("s1", Keyset.SERVERS, servers), ks.primary);
        s.refreshIfStale(KeysetService.MAX_AGE);
        served = envelope(doc().version(2).key("s1", Keyset.SERVERS, servers).revoke("s1"), ks.primary);
        assertEquals(1, s.refreshIfStale(KeysetService.MAX_AGE).serverKeys(now).size(), "throttled: no second fetch yet");
        assertEquals(1, fetches.get());
        now = now.plus(KeysetService.MAX_AGE).plus(Duration.ofSeconds(1));
        assertTrue(s.refreshIfStale(KeysetService.MAX_AGE).serverKeys(now).isEmpty(), "revoked key dropped");
        assertEquals(2, fetches.get());
    }
}
