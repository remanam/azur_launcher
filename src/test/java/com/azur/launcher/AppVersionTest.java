package com.azur.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AppVersionTest {

    @Test
    void versionComesFromTheBuildNotFromALiteral() {
        assertTrue(AppVersion.APP.matches("\\d+(\\.\\d+)+"), AppVersion.APP);
        assertEquals(System.getProperty("azur.expectedVersion"), AppVersion.APP);
    }
}
