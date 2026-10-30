package com.azur.launcher.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class MirrorsTest {

    private static final String URL = "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.1/a.jar";

    @Test
    void mirrorFirstThenOfficial() {
        assertEquals(List.of("https://m.example.org/mirror/maven.neoforged.net/releases/net/neoforged/neoforge/21.1.1/a.jar", URL),
                Mirrors.candidates(URL, "https://m.example.org/mirror/"));
    }

    @Test
    void noMirrorOrOtherHostsStayOfficial() {
        assertEquals(List.of(URL), Mirrors.candidates(URL, ""));
        assertEquals(List.of(URL), Mirrors.candidates(URL, null));
        assertNull(Mirrors.mirrorUrl("https://piston-meta.mojang.com/x", "https://m.example.org"));
    }

    @Test
    void librariesHostIsMirroredToo() {
        assertEquals("http://127.0.0.1:8782/libraries.minecraft.net/com/a/b.jar",
                Mirrors.mirrorUrl("https://libraries.minecraft.net/com/a/b.jar", "http://127.0.0.1:8782"));
    }
}
