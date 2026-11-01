package com.azur.launcher.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class JavaRuntimeDownloaderTest {

    @Test
    void mapsPlatforms() {
        assertEquals("mac-os-arm64", JavaRuntimeDownloader.platformKey("Mac OS X", "aarch64").get());
        assertEquals("mac-os", JavaRuntimeDownloader.platformKey("Mac OS X", "x86_64").get());
        assertEquals("windows-x64", JavaRuntimeDownloader.platformKey("Windows 11", "amd64").get());
        assertEquals("linux", JavaRuntimeDownloader.platformKey("Linux", "amd64").get());
        assertTrue(JavaRuntimeDownloader.platformKey("Linux", "riscv64").isEmpty());
    }

    @Test
    void findsComponentByMajor() {
        var platform = JsonParser.parseString("""
                {"java-runtime-alpha":[{"version":{"name":"16.0.1.9.1"}}],
                 "java-runtime-delta":[{"version":{"name":"21.0.7"}}],
                 "minecraft-java-exe":[]}""").getAsJsonObject();
        assertEquals("java-runtime-delta", JavaRuntimeDownloader.componentFor(platform, 21).get());
        assertTrue(JavaRuntimeDownloader.componentFor(platform, 25).isEmpty());
    }
}
