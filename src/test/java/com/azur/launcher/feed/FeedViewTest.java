package com.azur.launcher.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

class FeedViewTest {

    private static final FeedFilter.Context CTX = new FeedFilter.Context(Instant.parse("2026-11-05T12:00:00Z"), "1.0.0", "mac", "isles");

    private static FeedDocument doc(FeedNotice... notices) {
        return new FeedDocument("g", List.of(notices), List.of(new FeedNews("a", new FeedText("Old", ""), "2026-10-01", ""),
                new FeedNews("b", new FeedText("New", ""), "2026-11-01", "")));
    }

    @Test
    void closedBannerIsRememberedById(@TempDir Path dir) {
        Path file = dir.resolve("state.txt");
        FeedNotice info = FeedTestSupport.notice("n1", FeedNotice.Severity.WARN);
        FeedState state = new FeedState(file);
        assertEquals("n1", FeedView.of(doc(info), CTX, state).banner().orElseThrow().id());
        state.close(info);
        FeedView after = FeedView.of(doc(info), CTX, new FeedState(file)); // survives a restart
        assertTrue(after.banner().isEmpty());
        assertEquals(1, after.active().size(), "history keeps it");
    }

    @Test
    void unreadCountAndRead(@TempDir Path dir) {
        FeedState state = new FeedState(dir.resolve("s.txt"));
        FeedDocument d = doc(FeedTestSupport.notice("a", FeedNotice.Severity.INFO), FeedTestSupport.notice("b", FeedNotice.Severity.INFO));
        assertEquals(2, FeedView.of(d, CTX, state).unread());
        state.markRead(FeedView.of(d, CTX, state).active());
        assertEquals(0, FeedView.of(d, CTX, state).unread());
    }

    @Test
    void criticalBlocksPlayAndCannotBeClosed(@TempDir Path dir) {
        FeedState state = new FeedState(dir.resolve("s.txt"));
        FeedNotice critical = FeedTestSupport.notice("c", FeedNotice.Severity.CRITICAL);
        state.close(critical);
        FeedView view = FeedView.of(doc(critical), CTX, state);
        assertTrue(view.blocksPlay());
        assertEquals("c", view.banner().orElseThrow().id());
        assertFalse(FeedView.of(doc(FeedTestSupport.notice("w", FeedNotice.Severity.WARN)), CTX, state).blocksPlay());
    }

    @Test
    void expiredCriticalDoesNotBlockAndNewestNewsShown(@TempDir Path dir) {
        FeedNotice gone = new FeedNotice("c", FeedNotice.Severity.CRITICAL, new FeedText("t", ""), new FeedText("b", ""), "", "",
                "2026-11-01", "", "", List.of(), List.of(), "");
        FeedView view = FeedView.of(doc(gone), CTX, new FeedState(dir.resolve("s.txt")));
        assertFalse(view.blocksPlay());
        assertEquals("b", view.latestNews().orElseThrow().id());
    }
}
