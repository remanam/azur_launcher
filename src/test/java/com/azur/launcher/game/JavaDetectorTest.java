package com.azur.launcher.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class JavaDetectorTest {

    private static JavaDetector.JavaInfo java(String version, String arch) {
        return new JavaDetector.JavaInfo(Path.of("/jdk/" + version), Path.of("/jdk/" + version + "/bin/java"),
                version, JavaDetector.parseMajorVersion(version), arch);
    }

    @Test
    void parsesMajorVersions() {
        assertEquals(8, JavaDetector.parseMajorVersion("1.8.0_402"));
        assertEquals(17, JavaDetector.parseMajorVersion("17.0.9"));
        assertEquals(21, JavaDetector.parseMajorVersion("21"));
        assertEquals(-1, JavaDetector.parseMajorVersion("abc"));
        assertEquals(-1, JavaDetector.parseMajorVersion(null));
    }

    @Test
    void comparesVersionsNumerically() {
        assertTrue(JavaDetector.compareVersion("21.0.10", "21.0.9") > 0);
        assertEquals(0, JavaDetector.compareVersion("21.0", "21.0.0"));
        assertTrue(JavaDetector.compareVersion("17.0.9", "21.0.1") < 0);
    }

    @Test
    void picksNewestExactMajorAndSkipsTooSmallArch() {
        JavaDetector.JavaInfo old = java("21.0.2", "aarch64");
        JavaDetector.JavaInfo newer = java("21.0.4", "aarch64");
        JavaDetector.JavaInfo other = java("17.0.9", "aarch64");
        assertSame(newer, JavaDetector.pickBest(List.of(old, newer, other), 21));
        assertNull(JavaDetector.pickBest(List.of(other), 21));
    }

    @Test
    void fallbackPrefersTheClosestLowerThenHigherMajor() {
        JavaDetector.JavaInfo j8 = java("1.8.0_402", "x86_64");
        JavaDetector.JavaInfo j17 = java("17.0.9", "x86_64");
        JavaDetector.JavaInfo j25 = java("25.0.1", "x86_64");
        assertSame(j17, JavaDetector.pickFallback(List.of(j8, j17, j25), 21));
        assertSame(j25, JavaDetector.pickFallback(List.of(j25), 21));
        assertNull(JavaDetector.pickFallback(List.of(), 21));
    }
}
