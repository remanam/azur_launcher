package com.azur.launcher.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativeExtractorTest {

    private static Path zip(Path dir, String... names) throws IOException {
        Path zip = dir.resolve("n.zip");
        try (OutputStream out = Files.newOutputStream(zip); ZipOutputStream z = new ZipOutputStream(out)) {
            for (String name : names) {
                z.putNextEntry(new ZipEntry(name));
                z.write(1);
                z.closeEntry();
            }
        }
        return zip;
    }

    @Test
    void extractsAndSkipsExcludedAndMeta(@TempDir Path dir) throws IOException {
        Path z = zip(dir, "lib.so", "META-INF/x", "skip/y.so");
        assertEquals(1, NativeExtractor.extract(z, dir.resolve("out"), List.of("skip/")));
        assertTrue(Files.exists(dir.resolve("out/lib.so")));
    }

    @Test
    void zipSlipEntryFailsAndWritesNothingOutside(@TempDir Path dir) throws IOException {
        Path z = zip(dir, "../evil.so");
        assertThrows(IOException.class, () -> NativeExtractor.extract(z, dir.resolve("out"), null));
        assertFalse(Files.exists(dir.resolve("evil.so")));
    }
}
