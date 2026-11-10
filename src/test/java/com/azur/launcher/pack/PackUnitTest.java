package com.azur.launcher.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

class PackUnitTest {

    @Test
    void packSourceNeedsUrlAndAValidKey() {
        byte[] key = new byte[32];
        key[0] = 7;
        PackSource ok = new PackSource(" https://pack.example ", Base64.getEncoder().encodeToString(key));
        assertTrue(ok.present());
        assertEquals("https://pack.example", ok.url());
        assertFalse(PackSource.NONE.present());
        assertFalse(new PackSource(null, null).present());
        assertEquals(PackException.NO_PACK, assertThrows(PackException.class,
                () -> new PackServer(new PackSource("https://x.example", "AAAA"), null)).code());
    }

    @Test
    void unsafePathsAreRefused() {
        assertTrue(PackPaths.isSafeRel("mods/a.jar"));
        assertTrue(PackPaths.isSafeRel("servers.dat"));
        assertFalse(PackPaths.isSafeRel("options.txt"));
        for (String bad : List.of("../mods/a.jar", "mods/../x/a", "/mods/a.jar", "mods\\a.jar", "saves/level.dat",
                "mods", "mods//a", "mods/a.", "C:/x/y", "mods/./a")) {
            assertFalse(PackPaths.isSafeRel(bad), bad);
        }
    }

    @Test
    void globMatching() {
        assertTrue(PackPaths.match("mods/user_*.jar", "mods/user_x.jar"));
        assertTrue(PackPaths.match("sodium*", "mods/Sodium-1.jar"));
        assertTrue(PackPaths.match("spark/**", "spark/a/b"));
        assertFalse(PackPaths.match("mods/user_*.jar", "mods/other.jar"));
    }

    @Test
    void mergeAppendsOnlyMissingLines() {
        assertEquals("a\nb\nc\n", PackClient.mergeLines("a\nb\n", "a\nc\n"));
        assertEquals("a\n", PackClient.mergeLines("a\n", "a\n"));
    }

    @Test
    void versionOrder() {
        assertTrue(PackClient.compareVersion("0.1.0", "0.2") < 0);
        assertEquals(0, PackClient.compareVersion("1.0", "1"));
        assertTrue(PackClient.compareVersion("", "0.1.0") < 0);
    }
}
