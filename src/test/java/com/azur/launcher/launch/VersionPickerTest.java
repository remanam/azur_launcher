package com.azur.launcher.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azur.launcher.game.LocalVersion;
import com.azur.launcher.servers.ServerEntry;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class VersionPickerTest {

    private static LocalVersion version(String id, boolean ready) {
        return new LocalVersion(id, Path.of(id), ready, ready, "release", "");
    }

    private static ServerEntry server(String neoforge) {
        return new ServerEntry("s", "S", "h", 25565, "1.21.1", neoforge, "", "", "", "", false, "");
    }

    @Test
    void picksTheVersionWithTheServersNeoForgeBuild() {
        List<LocalVersion> installed = List.of(version("1.21.1", true), version("neoforge-21.1.264", true),
                version("neoforge-21.1.100", true));
        assertEquals("neoforge-21.1.264", VersionPicker.pick(installed, server("21.1.264"), "").get().id());
    }

    @Test
    void ignoresBrokenVersionsAndMissingBuilds() {
        assertTrue(VersionPicker.pick(List.of(version("neoforge-21.1.264", false)), server("21.1.264"), "").isEmpty());
        assertTrue(VersionPicker.pick(List.of(version("1.21.1", true)), server("21.1.264"), "").isEmpty());
    }

    @Test
    void withoutABuildTheSelectedVersionIsUsed() {
        List<LocalVersion> installed = List.of(version("a", true), version("b", true));
        assertEquals("b", VersionPicker.pick(installed, server(""), "b").get().id());
        assertTrue(VersionPicker.pick(installed, server(""), "zzz").isEmpty());
    }
}
