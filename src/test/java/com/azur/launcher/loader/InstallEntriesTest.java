package com.azur.launcher.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class InstallEntriesTest {

    private static final List<String> VANILLA = List.of("1.21.1", "1.21", "1.20.4");
    private static final List<String> BUILDS = List.of("21.1.9", "21.1.264", "21.0.5", "20.4.80", "19.0.1", "bad");

    @Test
    void mapsBuildsToMinecraft() {
        assertEquals("1.21.1", InstallEntries.mcFor("21.1.264"));
        assertEquals("1.21", InstallEntries.mcFor("21.0.5"));
        assertEquals("1.20.4", InstallEntries.mcFor("20.4.0-beta"));
        assertNull(InstallEntries.mcFor("20.1.5"));
        assertNull(InstallEntries.mcFor("19.0.1"));
        assertNull(InstallEntries.mcFor("../../x"));
    }

    @Test
    void validatesManualVersion() {
        assertTrue(InstallEntries.isValidNeoForge("21.1.264"));
        assertTrue(InstallEntries.isValidNeoForge("21.1.0-beta"));
        assertFalse(InstallEntries.isValidNeoForge("21.1"));
        assertFalse(InstallEntries.isValidNeoForge("21.1.264/../x"));
        assertFalse(InstallEntries.isValidNeoForge(" "));
        assertFalse(InstallEntries.isValidNeoForge(null));
    }

    @Test
    void combinedListIsPinnedFirstThenNewestFirst() {
        List<InstallEntries.Entry> list = InstallEntries.build(VANILLA, BUILDS, "1.21.1", "21.1.9");
        assertEquals(new InstallEntries.Entry("1.21.1", "21.1.9", true), list.get(0));
        assertEquals(new InstallEntries.Entry("1.21.1", null, false), list.get(1));
        assertEquals("21.1.264", list.get(2).neoforge());
        assertEquals(List.of("1.21.1", "1.21.1", "1.21.1", "1.21", "1.21", "1.20.4", "1.20.4"),
                list.stream().map(InstallEntries.Entry::mc).toList());
        assertEquals(1, list.stream().filter(e -> "21.1.9".equals(e.neoforge())).count());
    }

    @Test
    void worksWithoutNeoForgeList() {
        List<InstallEntries.Entry> list = InstallEntries.build(VANILLA, List.of(), null, null);
        assertEquals(3, list.size());
        assertTrue(list.stream().allMatch(InstallEntries.Entry::isVanilla));
    }

    @Test
    void boundsBuildsPerMinecraftVersion() {
        List<String> many = new java.util.ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            many.add("21.1." + i);
        }
        assertEquals(41, InstallEntries.build(List.of("1.21.1"), many, null, null).size());
    }

    @Test
    void searchMatchesAllWords() {
        InstallEntries.Entry nf = new InstallEntries.Entry("1.21.1", "21.1.264", false);
        InstallEntries.Entry van = new InstallEntries.Entry("1.21.1", null, false);
        assertTrue(InstallEntries.matches(nf, ""));
        assertTrue(InstallEntries.matches(nf, "NeoForge 1.21"));
        assertTrue(InstallEntries.matches(nf, "21.1.264"));
        assertFalse(InstallEntries.matches(van, "neoforge"));
        assertTrue(InstallEntries.matches(van, "vanilla"));
        assertFalse(InstallEntries.matches(nf, "1.20"));
    }
}
