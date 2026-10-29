package com.azur.launcher.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NeoForgeListTest {

    @TempDir
    Path dir;

    @Test
    void freshListIsSavedAndReturned() {
        NeoForgeList list = new NeoForgeList(dir.resolve("c/nf.txt"));
        NeoForgeList.Result r = list.load(() -> List.of("21.1.264", "21.1.264", "junk", "20.4.1"));
        assertTrue(r.fresh());
        assertEquals(List.of("21.1.264", "20.4.1"), r.versions());
        assertEquals(r.versions(), list.cached());
    }

    @Test
    void failureFallsBackToLastGoodCopyWithReason() {
        NeoForgeList list = new NeoForgeList(dir.resolve("nf.txt"));
        list.load(() -> List.of("21.1.1"));
        NeoForgeList.Result r = list.load(() -> {
            throw new IOException("connect timed out");
        });
        assertFalse(r.fresh());
        assertEquals("connect timed out", r.error());
        assertEquals(List.of("21.1.1"), r.versions());
    }

    @Test
    void failureWithoutCacheGivesEmptyListAndReason() {
        NeoForgeList.Result r = new NeoForgeList(dir.resolve("none.txt")).load(() -> {
            throw new IOException();
        });
        assertTrue(r.versions().isEmpty());
        assertEquals("IOException", r.error());
    }

    @Test
    void emptyFreshListCountsAsFailure() {
        NeoForgeList list = new NeoForgeList(dir.resolve("nf.txt"));
        list.load(() -> List.of("21.1.1"));
        NeoForgeList.Result r = list.load(List::of);
        assertFalse(r.fresh());
        assertEquals(List.of("21.1.1"), r.versions());
    }

    @Test
    void cacheIsBoundedAndIgnoresGarbage() throws IOException {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < NeoForgeList.MAX_LINES + 50; i++) {
            lines.add("21." + (i / 1000) + "." + (i % 1000 + 1));
        }
        assertEquals(NeoForgeList.MAX_LINES, NeoForgeList.clean(lines).size());
        Path big = dir.resolve("big.txt");
        Files.write(big, new byte[200 * 1024]);
        assertTrue(new NeoForgeList(big).cached().isEmpty());
    }

    @Test
    void parsesAllVersionsFromMetadata() {
        String xml = "<versions><version>21.1.1</version><version> 20.4.2 </version></versions>";
        assertEquals(List.of("21.1.1", "20.4.2"), NeoForgeCatalog.parseAll(xml));
    }
}
