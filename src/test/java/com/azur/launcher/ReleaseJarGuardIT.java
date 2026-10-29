package com.azur.launcher;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/** Verify-phase guards (see pom.xml): the packaged RELEASE jar and app image carry no offline-account code. */
class ReleaseJarGuardIT {

    /** Plain http endpoints and the third-party mirrors the owner ruled out (the Xbox relying-party id is an identifier, not a connection). */
    private static final Pattern FORBIDDEN_ENDPOINTS = Pattern.compile(
            "http://(?!localhost|127\\.0\\.0\\.1|www\\.w3\\.org|javafx\\.com|java\\.sun\\.com|xmlns|schemas|auth\\.xboxlive\\.com)[a-z0-9.-]+"
                    + "|bmclapi|mcbbs|gitee\\.com|mirror\\.[a-z]+\\.cn|bangbang93",
            Pattern.CASE_INSENSITIVE);

    private static Path releaseJar() {
        String property = System.getProperty("azur.jar");
        assertTrue(property != null && Files.isRegularFile(Path.of(property)), "azur.jar not set or missing");
        return Path.of(property);
    }

    @Test
    void releaseJarHasNoOfflineAccountPath() throws Exception {
        List<String> hits = ReleaseJarScanner.findHits(releaseJar());
        assertTrue(hits.isEmpty(), "Offline references in the release jar: " + hits);
    }

    @Test
    void releaseJarHasNoPlainHttpOrMirrors() throws Exception {
        // third-party binaries (JavaFX natives, PNG metadata, Windows runtime DLLs) carry vendor URLs; our code and text are scanned
        List<String> hits = ReleaseJarScanner.findPattern(releaseJar(), FORBIDDEN_ENDPOINTS).stream()
                .filter(hit -> !hit.startsWith("natives/") && !hit.contains(".png:")).toList();
        assertTrue(hits.isEmpty(), "Plain http or mirror hosts in the release jar: " + hits);
    }

    /** Runs only for the dist profile: the jpackage image holds exactly the guarded jar and clean updater builds. */
    @Test
    void packagedImageContainsTheGuardedJar() throws Exception {
        String image = System.getProperty("azur.image");
        assumeTrue(image != null && !image.isBlank(), "no app image to scan");
        try (Stream<Path> all = Files.walk(Path.of(image))) {
            List<Path> files = all.filter(Files::isRegularFile).toList();
            List<Path> jars = files.stream().filter(f -> f.getFileName().toString().equals("azur-launcher.jar")).toList();
            assertFalse(jars.isEmpty(), "azur-launcher.jar is missing from the image " + image);
            for (Path jar : jars) {
                assertArrayEquals(Files.readAllBytes(releaseJar()), Files.readAllBytes(jar), "image jar differs");
            }
            for (Path updater : files.stream().filter(f -> f.getFileName().toString().startsWith("azur-updater")).toList()) {
                assertFalse(ReleaseJarScanner.fileHasForbidden(updater), "offline reference in " + updater);
            }
            assertTrue(files.stream().noneMatch(f -> f.getFileName().toString().contains("debug")),
                    "a debug artifact is in the image");
        }
    }

    /** Runs only when the cross-platform jar is guarded (profile fat): natives of all four targets are inside. */
    @Test
    void fatJarCarriesNativesForEveryPlatform() throws Exception {
        assumeTrue(Boolean.getBoolean("azur.fat"), "not the fat jar");
        try (java.util.jar.JarFile file = new java.util.jar.JarFile(releaseJar().toFile())) {
            List<String> names = java.util.Collections.list(file.entries()).stream().map(java.util.jar.JarEntry::getName).toList();
            for (String entry : List.of("natives/win/glass.dll", "natives/mac-aarch64/libglass.dylib",
                    "natives/mac/libglass.dylib", "natives/linux/libglass.so")) {
                assertTrue(names.contains(entry), "missing " + entry);
            }
        }
    }
}
