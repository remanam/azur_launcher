package com.azur.launcher.servers;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BuiltinServersTest {

    @Test
    void genericBuildHasNoBuiltinServer() {
        // a branded build supplies builtin.* in branding.properties; the public tree ships none
        if (BuiltinServers.isles() == null) {
            assertTrue(BuiltinServers.all().isEmpty());
        } else {
            assertTrue(BuiltinServers.all().contains(BuiltinServers.isles()));
        }
        assertNull(ServerChoice.pick(java.util.List.of(), ""));
    }
}
