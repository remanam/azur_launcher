package com.azur.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FxNativesTest {

    @Test
    void picksThePlatformSet() {
        assertEquals("win", FxNatives.platform("Windows 11", "amd64"));
        assertEquals("mac-aarch64", FxNatives.platform("Mac OS X", "aarch64"));
        assertEquals("mac", FxNatives.platform("Mac OS X", "x86_64"));
        assertEquals("linux", FxNatives.platform("Linux", "amd64"));
    }
}
