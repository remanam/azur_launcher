package com.azur.launcher.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HostPolicyTest {

    @Test
    void officialHostsOverHttpsOnly() {
        HostPolicy p = HostPolicy.official();
        assertTrue(p.allows("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"));
        assertTrue(p.allows("https://maven.neoforged.net/releases/x.jar"));
        assertTrue(p.allows("https://libraries.minecraft.net/a.jar"));
        assertFalse(p.allows("http://piston-meta.mojang.com/x"));
        assertFalse(p.allows("https://bmclapi2.bangbang93.com/x"));
        assertFalse(p.allows("https://piston-meta.mojang.com.evil.example/x"));
        assertFalse(p.allows("https://user@piston-meta.mojang.com/x"));
        assertFalse(p.allows("https://piston-meta.mojang.com:8443/x"));
    }

    /** Own hosts (mirror, address of the signed server list) are added next to the official ones, never instead. */
    @Test
    void ownHostsAreAddedNextToTheOfficialOnes() {
        HostPolicy p = HostPolicy.withHosts(java.util.Set.of("pack.azur.example"));
        assertTrue(p.allows("https://pack.azur.example/servers.signed.json"));
        assertTrue(p.allows("https://libraries.minecraft.net/a.jar"));
        assertFalse(p.allows("https://other.example/a"));
        assertFalse(p.allows("http://pack.azur.example/servers.signed.json"));
        assertFalse(HostPolicy.withHosts(java.util.Set.of()).allows("https://pack.azur.example/a"));
    }
}
