package com.azur.launcher.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HttpUtilsTest {

    @Test
    void httpIsUpgradedToHttps() {
        assertEquals("https://piston-meta.mojang.com/a", HttpUtils.secure("http://piston-meta.mojang.com/a"));
        assertEquals("https://libraries.minecraft.net/x", HttpUtils.secure("https://libraries.minecraft.net/x"));
        assertEquals("https://x/y", HttpUtils.secure("HTTP://x/y"));
    }

    @Test
    void sha1OfKnownContent(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.txt");
        Files.writeString(file, "abc");
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", HttpUtils.sha1(file));
    }
}
