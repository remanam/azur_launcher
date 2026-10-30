package com.azur.launcher.loader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

class NeoForgeCatalogTest {

    @Test
    void prefixFromMinecraftVersion() {
        assertEquals("21.1", NeoForgeCatalog.versionPrefix("1.21.1"));
        assertEquals("21.0", NeoForgeCatalog.versionPrefix("1.21"));
        assertEquals("20.4", NeoForgeCatalog.versionPrefix("1.20.4"));
        assertNull(NeoForgeCatalog.versionPrefix("1.20.1"));
        assertNull(NeoForgeCatalog.versionPrefix("26w01a"));
    }

    @Test
    void filtersAndSortsNewestFirstWithReleaseAboveBeta() throws IOException {
        String xml = "<versions><version>21.0.5</version><version>21.1.2</version><version>21.1.10</version>"
                + "<version>21.1.10-beta</version><version>21.1.9</version></versions>";
        assertEquals(List.of("21.1.10", "21.1.10-beta", "21.1.9", "21.1.2"), NeoForgeCatalog.filter(xml, "21.1"));
        assertThrows(IOException.class, () -> NeoForgeCatalog.filter(xml, "22.0"));
    }

    @Test
    void installerUrlIsHttpsOnTheOfficialMaven() {
        String url = NeoForgeCatalog.installerUrl("21.1.172");
        assertTrue(url.startsWith("https://maven.neoforged.net/"));
        assertTrue(url.endsWith("/21.1.172/neoforge-21.1.172-installer.jar"));
    }

    @Test
    void installerCommandIsBuiltWithoutShell() {
        List<String> command = NeoForgeInstaller.command(Path.of("/jdk/bin/java"), Path.of("/c/i.jar"), Path.of("/g"));
        assertEquals("--installClient", command.get(command.size() - 2));
        assertEquals(Path.of("/g").toString(), command.get(command.size() - 1));
    }

    @Test
    void installerGetsTheLauncherProxy() {
        com.azur.launcher.net.ProxyConfig.set(com.azur.launcher.net.ProxyConfig.of("proxy.test", 3128, "u", "p"));
        try {
            List<String> command = NeoForgeInstaller.command(Path.of("/jdk/bin/java"), Path.of("/c/i.jar"), Path.of("/g"));
            org.junit.jupiter.api.Assertions.assertTrue(command.contains("-Dhttps.proxyHost=proxy.test"));
            org.junit.jupiter.api.Assertions.assertTrue(command.contains("-Djdk.http.auth.tunneling.disabledSchemes="));
            org.junit.jupiter.api.Assertions.assertTrue(command.indexOf("-jar") > command.indexOf("-Dhttps.proxyPort=3128"));
        } finally {
            com.azur.launcher.net.ProxyConfig.set(com.azur.launcher.net.ProxyConfig.NONE);
        }
    }
}
