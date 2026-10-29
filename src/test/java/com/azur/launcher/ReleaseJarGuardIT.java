package com.azur.launcher;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Verify-phase guard (see pom.xml): the packaged RELEASE jar must contain no offline-account code. */
class ReleaseJarGuardIT {

    @Test
    void releaseJarHasNoOfflineAccountPath() throws Exception {
        String property = System.getProperty("azur.jar");
        assertTrue(property != null && Files.isRegularFile(Path.of(property)), "azur.jar not set or missing");
        List<String> hits = ReleaseJarScanner.findHits(Path.of(property));
        assertTrue(hits.isEmpty(), "Offline references in the release jar: " + hits);
    }
}
