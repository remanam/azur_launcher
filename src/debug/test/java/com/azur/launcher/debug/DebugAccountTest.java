package com.azur.launcher.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azur.launcher.auth.DebugAccounts;

import org.junit.jupiter.api.Test;

/** Compiled and run only with {@code -Pdebug}. */
class DebugAccountTest {

    @Test
    void providerIsFoundAndCreatesAStableLaunchableAccount() {
        var provider = DebugAccounts.provider().orElseThrow();
        var a = provider.create("Dev");
        assertTrue(a.launchPermitted());
        assertEquals(a.getUuid(), provider.create("Dev").getUuid());
        assertEquals("Player", provider.create(" ").getUsername());
        assertTrue(provider.buttonLabel().startsWith("DEBUG"));
    }
}
