package com.azur.launcher.servers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;

class BuiltinServersTest {

    @Test
    void builtinDefaultIsCompleteAndChosenByDefault() {
        ServerEntry e = BuiltinServers.isles();
        assertEquals("isles", e.id());
        assertEquals(32, Base64.getDecoder().decode(e.pack().key()).length);
        assertTrue(e.pack().url().startsWith("https://"));
        assertTrue(e.mirror().startsWith("https://"));
        assertFalse(e.address().isBlank());
        assertEquals(e, ServerChoice.pick(BuiltinServers.all(), ""));
    }
}
