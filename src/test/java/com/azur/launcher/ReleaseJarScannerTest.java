package com.azur.launcher;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Proves the guard really fails on offline code and passes on the whitelisted OAuth scope. */
class ReleaseJarScannerTest {

    private static Path jar(Path dir, String name, String body) throws IOException {
        Path jar = dir.resolve("t.jar");
        try (OutputStream out = Files.newOutputStream(jar); JarOutputStream jos = new JarOutputStream(out)) {
            jos.putNextEntry(new JarEntry(name));
            jos.write(body.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
        return jar;
    }

    @Test
    void oauthScopeIsAllowed(@TempDir Path dir) throws IOException {
        assertTrue(ReleaseJarScanner.findHits(jar(dir, "A.class", "XboxLive.signin offline_access")).isEmpty());
    }

    @Test
    void classNameAndContentAreCaughtCaseInsensitively(@TempDir Path dir) throws IOException {
        assertFalse(ReleaseJarScanner.findHits(jar(dir, "x/OfflineAccount.class", "abc")).isEmpty());
        assertFalse(ReleaseJarScanner.findHits(jar(dir, "B.class", "play OFFLINE mode")).isEmpty());
    }
}
