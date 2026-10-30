package com.azur.launcher.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class PackUnitTest {

    @Test
    void inviteRoundTripAndRejection() throws Exception {
        byte[] key = new byte[32];
        key[0] = 7;
        InviteToken t = InviteToken.parse(InviteToken.encode("https://pack.example", "tok", key));
        assertEquals("https://pack.example", t.url());
        assertEquals("tok", t.token());
        assertArrayEquals(key, t.publicKey());
        assertEquals(PackException.INVITE, assertThrows(PackException.class, () -> InviteToken.parse("nope")).code());
        assertEquals(PackException.INVITE,
                assertThrows(PackException.class, () -> InviteToken.parse("azur1.@@@")).code());
        assertEquals(PackException.INVITE,
                assertThrows(PackException.class, () -> InviteToken.parse(InviteToken.encode("ftp://x", "t", key))).code());
    }

    @Test
    void unsafePathsAreRefused() {
        assertTrue(PackPaths.isSafeRel("mods/a.jar"));
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
