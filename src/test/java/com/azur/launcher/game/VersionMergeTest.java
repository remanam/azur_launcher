package com.azur.launcher.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azur.launcher.model.VersionMeta;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VersionMergeTest {

    @Test
    void loaderArgumentsAreAppendedToVanillaOnes(@TempDir Path root) throws Exception {
        GamePaths paths = new GamePaths(root);
        write(paths.versionJson("1.21.1"),
                "{\"id\":\"1.21.1\",\"mainClass\":\"m\",\"arguments\":{\"game\":[\"--username\",\"${auth_player_name}\"],"
                        + "\"jvm\":[\"-cp\",\"${classpath}\"]}}");
        write(paths.versionJson("neoforge-1"),
                "{\"id\":\"neoforge-1\",\"inheritsFrom\":\"1.21.1\",\"mainClass\":\"b\",\"arguments\":{\"game\":[\"--launchTarget\",\"x\"],"
                        + "\"jvm\":[\"-p\",\"mods\"]}}");
        VersionMeta meta = new VersionManager(paths, s -> { }).getVersionMeta("neoforge-1", false);
        assertEquals(4, meta.getArguments().getGame().size());
        assertEquals(4, meta.getArguments().getJvm().size());
        assertEquals("-cp", meta.getArguments().getJvm().get(0).getValue().getAsString());
        assertTrue("--username".equals(meta.getArguments().getGame().get(0).getValue().getAsString()));
        assertEquals("b", meta.getMainClass());
    }

    @Test
    void nativeClassifiersAreDistinctLibraries(@TempDir Path root) throws Exception {
        GamePaths paths = new GamePaths(root);
        write(paths.versionJson("1.21.1"), "{\"id\":\"1.21.1\",\"mainClass\":\"m\",\"libraries\":["
                + "{\"name\":\"org.lwjgl:lwjgl:3.3.3\"},{\"name\":\"org.lwjgl:lwjgl:3.3.3:natives-macos-arm64\"},"
                + "{\"name\":\"org.lwjgl:lwjgl:3.3.3:natives-windows\"}]}");
        VersionMeta meta = new VersionManager(paths, s -> { }).getVersionMeta("1.21.1", false);
        assertEquals(3, meta.getLibraries().size());
    }

    private static void write(Path file, String json) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, json);
    }
}
