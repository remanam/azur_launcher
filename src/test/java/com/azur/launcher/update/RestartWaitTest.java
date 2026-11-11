package com.azur.launcher.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class RestartWaitTest {

    @Test
    void noPidMeansNoWait() {
        assertTrue(RestartWait.awaitParent(null, p -> true, m -> { throw new AssertionError(); }, 1000));
        assertTrue(RestartWait.awaitParent("junk", p -> true, m -> { throw new AssertionError(); }, 1000));
        assertEquals(-1, RestartWait.parsePid("-4"));
        assertEquals(42, RestartWait.parsePid(" 42 "));
    }

    @Test
    void waitsUntilTheOldProcessIsGone() {
        AtomicInteger polls = new AtomicInteger();
        assertTrue(RestartWait.awaitParent("42", p -> polls.incrementAndGet() < 4, m -> { }, 15_000));
        assertEquals(4, polls.get());
    }

    @Test
    void givesUpAfterTheLimit() {
        AtomicInteger slept = new AtomicInteger();
        assertFalse(RestartWait.awaitParent("42", p -> true, m -> slept.incrementAndGet(), 1000));
        assertEquals(5, slept.get());
    }

    @Test
    void retriesLockedFilesThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger sleeps = new AtomicInteger();
        String result = RestartWait.retrying(5, m -> sleeps.incrementAndGet(), () -> {
            if (calls.incrementAndGet() < 3) {
                throw new UncheckedIOException(new java.io.IOException("locked"));
            }
            return "ok";
        });
        assertEquals("ok", result);
        assertEquals(2, sleeps.get());
    }

    @Test
    void retriesGiveUpAndRethrow() {
        AtomicInteger calls = new AtomicInteger();
        org.junit.jupiter.api.Assertions.assertThrows(UncheckedIOException.class, () -> RestartWait.retrying(3, m -> { },
                () -> {
                    calls.incrementAndGet();
                    throw new UncheckedIOException(new java.io.IOException("locked"));
                }));
        assertEquals(3, calls.get());
    }

    @Test
    void checkPeriodIsFiveMinutes() {
        assertEquals(5, com.azur.launcher.update.UpdateService.CHECK_PERIOD_MINUTES);
    }
}
