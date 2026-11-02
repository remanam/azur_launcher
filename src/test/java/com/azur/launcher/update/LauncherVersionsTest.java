package com.azur.launcher.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LauncherVersionsTest {

    @Test
    void comparesNumerically() {
        assertTrue(LauncherVersions.newer("1.10.0", "1.9.9"));
        assertTrue(LauncherVersions.newer("1.0.1", "1.0"));
        assertFalse(LauncherVersions.newer("1.0", "1.0.0"));
        assertEquals(0, LauncherVersions.compare("2", "2.0.0"));
        assertTrue(LauncherVersions.newer("1.2.3-beta", "1.2.2"));
        assertFalse(LauncherVersions.newer("garbage", "0.0.1"));
    }
}
