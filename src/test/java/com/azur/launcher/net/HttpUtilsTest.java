package com.azur.launcher.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", Hashes.sha1(file));
    }

    /** The signed server list lives on our own host, so it must become allowed before the first request. */
    @Test
    void ownHostsBecomeAllowedAndBadValuesAreIgnored() {
        HttpUtils.allowHosts("not a url", "http://plain.example/x", "");
        assertFalse(HttpUtils.downloader().policy().allows("https://plain.example/x"));
        HttpUtils.allowHosts("https://pack.azur.example/servers.signed.json");
        assertTrue(HttpUtils.downloader().policy().allows("https://pack.azur.example/x"));
        assertTrue(HttpUtils.downloader().policy().allows("https://libraries.minecraft.net/a.jar"));
    }
}
