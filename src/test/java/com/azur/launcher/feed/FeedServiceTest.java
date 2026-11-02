package com.azur.launcher.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

class FeedServiceTest {

    private final List<String> etagsSeen = new ArrayList<>();
    private byte[] body;
    private String etag = "\"v1\"";
    private boolean down;

    private FeedService service(Path dir, KeyPair key, AtomicBoolean busy) {
        return new FeedService(dir, "https://example.test/dl/feed.json", key.getPublic(), (url, tag) -> {
            etagsSeen.add(tag);
            if (down) {
                throw new IOException("offline");
            }
            if (!tag.isEmpty() && tag.equals(etag)) {
                return new FeedService.Response(304, etag, new byte[0]);
            }
            return new FeedService.Response(200, etag, body);
        }, busy::get, line -> { });
    }

    @Test
    void secondCheckIsA304AndCacheSurvivesRestart(@TempDir Path dir) throws Exception {
        KeyPair key = FeedTestSupport.newKey();
        body = FeedTestSupport.signed(FeedTestSupport.sample(), key);
        FeedService service = service(dir, key, new AtomicBoolean());
        assertTrue(service.refresh());
        assertFalse(service.refresh(), "304: nothing changed");
        assertEquals(List.of("", "\"v1\""), etagsSeen);
        down = true;
        FeedService restarted = service(dir, key, new AtomicBoolean());
        assertNotNull(restarted.current(), "last valid feed from the cache while offline");
        assertFalse(restarted.refresh());
        assertNotNull(restarted.current());
    }

    @Test
    void badSignatureKeepsTheLastValidFeed(@TempDir Path dir) throws Exception {
        KeyPair key = FeedTestSupport.newKey();
        body = FeedTestSupport.signed(FeedTestSupport.sample(), key);
        FeedService service = service(dir, key, new AtomicBoolean());
        service.refresh();
        etag = "\"v2\"";
        body = FeedTestSupport.signed(new FeedDocument("newer", List.of(), List.of()), FeedTestSupport.newKey());
        assertFalse(service.refresh());
        assertEquals("2026-11-02T12:00:00Z", service.current().generated());
    }

    @Test
    void badFeedWithoutCacheGivesNothing(@TempDir Path dir) throws Exception {
        KeyPair key = FeedTestSupport.newKey();
        body = "{}".getBytes();
        FeedService service = service(dir, key, new AtomicBoolean());
        assertFalse(service.refresh());
        assertNull(service.current());
    }

    @Test
    void noNetworkWhileTheGameRuns(@TempDir Path dir) throws Exception {
        KeyPair key = FeedTestSupport.newKey();
        body = FeedTestSupport.signed(FeedTestSupport.sample(), key);
        AtomicBoolean busy = new AtomicBoolean(true);
        FeedService service = service(dir, key, busy);
        assertFalse(service.refresh());
        assertTrue(etagsSeen.isEmpty());
        busy.set(false);
        assertTrue(service.refresh());
    }
}
