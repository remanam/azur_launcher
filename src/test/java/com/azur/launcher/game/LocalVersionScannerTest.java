package com.azur.launcher.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalVersionScannerTest {

    private static void version(Path root, String id, String json, boolean jar) throws IOException {
        Path dir = root.resolve("versions").resolve(id);
        Files.createDirectories(dir);
        if (json != null) {
            Files.writeString(dir.resolve(id + ".json"), json);
        }
        if (jar) {
            Files.writeString(dir.resolve(id + ".jar"), "x");
        }
    }

    @Test
    void classifiesVersionFolders(@TempDir Path root) throws IOException {
        version(root, "1.21.1", "{\"type\":\"release\"}", true);
        version(root, "neoforge-21.1.1", "{\"inheritsFrom\":\"1.21.1\"}", false);
        version(root, "nojar", "{\"type\":\"release\"}", false);
        version(root, "broken", "{ not json", true);
        version(root, "empty", null, false);

        List<LocalVersion> found = LocalVersionScanner.scan(root);
        assertEquals(5, found.size());
        assertEquals(LocalVersion.Status.DAMAGED, status(found, "broken"));
        assertEquals(LocalVersion.Status.DAMAGED, status(found, "empty"));
        assertEquals(LocalVersion.Status.READY, status(found, "1.21.1"));
        assertEquals(LocalVersion.Status.READY, status(found, "neoforge-21.1.1"));
        assertEquals(LocalVersion.Status.MISSING_JAR, status(found, "nojar"));
    }

    @Test
    void missingVersionsFolderGivesAnEmptyList(@TempDir Path root) {
        assertEquals(0, LocalVersionScanner.scan(root).size());
        assertEquals(0, LocalVersionScanner.scan(null).size());
    }

    private static LocalVersion.Status status(List<LocalVersion> all, String id) {
        return all.stream().filter(v -> v.id().equals(id)).findFirst().orElseThrow().status();
    }
}
