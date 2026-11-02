package com.azur.launcher.feed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

class FeedFilterTest {

    private static final Instant NOW = Instant.parse("2026-11-05T12:00:00Z");
    private static final FeedFilter.Context CTX = new FeedFilter.Context(NOW, "1.5.0", "mac", "isles");

    private static FeedNotice with(String starts, String expires, String min, String max, List<String> os,
                                   List<String> servers, String account) {
        return new FeedNotice("x", FeedNotice.Severity.INFO, new FeedText("t", ""), new FeedText("b", ""), "", starts,
                expires, min, max, os, servers, account);
    }

    @Test
    void periodIsHalfOpenAndAcceptsDates() {
        assertTrue(FeedFilter.applies(with("2026-11-05", "2026-11-06", "", "", List.of(), List.of(), ""), CTX));
        assertFalse(FeedFilter.applies(with("2026-11-06", "", "", "", List.of(), List.of(), ""), CTX), "not started");
        assertFalse(FeedFilter.applies(with("", "2026-11-05T12:00:00Z", "", "", List.of(), List.of(), ""), CTX), "expired");
        assertFalse(FeedFilter.applies(with("garbage", "", "", "", List.of(), List.of(), ""), CTX), "unreadable date hides it");
    }

    @Test
    void versionOsAndServerRestrictions() {
        assertTrue(FeedFilter.applies(with("", "", "1.5.0", "1.5.0", List.of(), List.of(), ""), CTX));
        assertFalse(FeedFilter.applies(with("", "", "1.6.0", "", List.of(), List.of(), ""), CTX));
        assertFalse(FeedFilter.applies(with("", "", "", "1.4.9", List.of(), List.of(), ""), CTX));
        assertTrue(FeedFilter.applies(with("", "", "", "", List.of("mac", "linux"), List.of(), ""), CTX));
        assertFalse(FeedFilter.applies(with("", "", "", "", List.of("windows"), List.of(), ""), CTX));
        assertFalse(FeedFilter.applies(with("", "", "", "", List.of(), List.of("other"), ""), CTX));
        assertTrue(FeedFilter.applies(with("", "", "", "", List.of(), List.of("isles"), ""), CTX));
    }

    @Test
    void personalNoticesAreReservedForStage3() {
        assertFalse(FeedFilter.applies(with("", "", "", "", List.of(), List.of(), "uuid-1"), CTX));
    }

    @Test
    void osNames() {
        assertEquals("mac", FeedFilter.osOf("Mac OS X"));
        assertEquals("windows", FeedFilter.osOf("Windows 11"));
        assertEquals("linux", FeedFilter.osOf("Linux"));
    }

    @Test
    void mostImportantFirst() {
        List<FeedNotice> sorted = FeedFilter.active(List.of(FeedTestSupport.notice("i", FeedNotice.Severity.INFO),
                FeedTestSupport.notice("c", FeedNotice.Severity.CRITICAL), FeedTestSupport.notice("w", FeedNotice.Severity.WARN)), CTX);
        assertEquals(List.of("c", "w", "i"), sorted.stream().map(FeedNotice::id).toList());
    }
}
