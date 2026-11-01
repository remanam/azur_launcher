package com.azur.launcher.servers;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class ServerChoiceTest {

    private static ServerEntry e(String id) {
        return new ServerEntry(id, id, "h.example", 25565, "1.21.1", "21.1.264", "", "21", "", com.azur.launcher.pack.PackSource.NONE, "");
    }

    @Test
    void oneServerHasNoSelector() {
        assertFalse(ServerChoice.selectorVisible(List.of(e("isles"))));
        assertEquals("isles", ServerChoice.pick(List.of(e("isles")), "").id());
    }

    @Test
    void twoServersShowSelectorAndRememberChoice() {
        List<ServerEntry> l = List.of(e("isles"), e("other"));
        assertTrue(ServerChoice.selectorVisible(l));
        assertEquals("other", ServerChoice.pick(l, "other").id());
    }

    @Test
    void unknownIdFallsBackToIslesThenFirst() {
        assertEquals("isles", ServerChoice.pick(List.of(e("x"), e("isles")), "gone").id());
        assertEquals("x", ServerChoice.pick(List.of(e("x"), e("y")), "gone").id());
        assertNull(ServerChoice.pick(List.of(), "isles"));
    }
}
