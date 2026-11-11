package com.azur.launcher.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourcePackDefaultsTest {

    private static final String F = "file/Faithful 32x - 1.21.1.zip";
    private static final String M = "file/Upscaled Mekanism 32x v1.3 - [1.20.1-1.21.1].zip";
    private static final String D = "file/azur_divine_32x.zip";

    @TempDir
    Path dir;

    private void install(String... names) throws IOException {
        Files.createDirectories(dir.resolve("resourcepacks"));
        for (String n : names) {
            Files.writeString(dir.resolve("resourcepacks").resolve(n), "z");
        }
    }

    private void all() throws IOException {
        install(ResourcePackDefaults.PACKS.toArray(new String[0]));
    }

    private String line() throws IOException {
        for (String l : Files.readString(dir.resolve("options.txt")).split("\r?\n")) {
            if (l.startsWith("resourcePacks:")) {
                return l;
            }
        }
        return "";
    }

    @Test
    void freshInstallOrder() throws IOException {
        all();
        OptionsDefaults.apply(dir, "ru");
        assertEquals("resourcePacks:[\"" + F + "\",\"" + M + "\",\"" + D + "\",\"vanilla\",\"mod_resources\"]", line());
        assertTrue(Files.exists(dir.resolve(ResourcePackDefaults.MARKER)));
    }

    @Test
    void noZipsNoLineNoMarker() throws IOException {
        OptionsDefaults.apply(dir, "ru");
        assertEquals("", line());
        assertFalse(Files.exists(dir.resolve(ResourcePackDefaults.MARKER)));
    }

    @Test
    void playerListKeptOursInsertedBeforeVanilla() throws IOException {
        all();
        Files.writeString(dir.resolve("options.txt"), "resourcePacks:[\"file/Mine.zip\",\"vanilla\",\"azur_sky\"]\n");
        OptionsDefaults.apply(dir, "ru");
        assertEquals("resourcePacks:[\"file/Mine.zip\",\"" + F + "\",\"" + M + "\",\"" + D + "\",\"vanilla\",\"azur_sky\"]", line());
    }

    @Test
    void secondRunAndPlayerRemovalRespected() throws IOException {
        all();
        OptionsDefaults.apply(dir, "ru");
        String own = "resourcePacks:[\"vanilla\",\"mod_resources\"]";
        Files.writeString(dir.resolve("options.txt"), own + "\nlang:ru_ru\nonboardAccessibility:false\nnarrator:0\n");
        OptionsDefaults.apply(dir, "ru");
        assertEquals(own, line());
    }

    @Test
    void newPackAddedLaterWithoutTouchingChoice() throws IOException {
        install(ResourcePackDefaults.PACKS.get(0));
        OptionsDefaults.apply(dir, "ru");
        install(ResourcePackDefaults.PACKS.get(2));
        OptionsDefaults.apply(dir, "ru");
        assertEquals("resourcePacks:[\"" + F + "\",\"" + D + "\",\"vanilla\",\"mod_resources\"]", line());
    }

    @Test
    void crlfKept() throws IOException {
        all();
        Files.writeString(dir.resolve("options.txt"), "fov:0.5\r\nresourcePacks:[\"vanilla\"]\r\n");
        OptionsDefaults.apply(dir, "ru");
        assertTrue(Files.readString(dir.resolve("options.txt")).contains("\"vanilla\"]\r\n"));
    }
}
