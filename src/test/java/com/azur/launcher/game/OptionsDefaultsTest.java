package com.azur.launcher.game;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OptionsDefaultsTest {

    @TempDir
    Path dir;

    private String read() throws IOException {
        return Files.readString(dir.resolve("options.txt"));
    }

    @Test
    void createsFileRussian() throws IOException {
        OptionsDefaults.apply(dir, "ru");
        assertEquals("lang:ru_ru\nonboardAccessibility:false\nnarrator:0\ntutorialStep:none\n", read());
    }

    @Test
    void englishAndUnknownLanguage() throws IOException {
        OptionsDefaults.apply(dir, "en");
        assertEquals(true, read().startsWith("lang:en_us\n"));
    }

    @Test
    void appendsOnlyMissingKeepsCrlf() throws IOException {
        Files.writeString(dir.resolve("options.txt"), "fov:0.5\r\nnarrator:1", java.nio.charset.StandardCharsets.UTF_8);
        OptionsDefaults.apply(dir, "ru");
        assertEquals("fov:0.5\r\nnarrator:1\r\nlang:ru_ru\r\nonboardAccessibility:false\r\ntutorialStep:none\r\n", read());
    }

    @Test
    void existingKeysUntouched() throws IOException {
        String own = "lang:de_de\nnarrator:2\nonboardAccessibility:true\n";
        Files.writeString(dir.resolve("options.txt"), own);
        OptionsDefaults.apply(dir, "ru");
        assertEquals(own, read());
    }
}
