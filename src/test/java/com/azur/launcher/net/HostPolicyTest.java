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

    /** Local-test exception: plain http only for loopback literals. */
    @Test
    void loopbackHttpIsTheOnlyHttpException() {
        HostPolicy p = HostPolicy.official();
        assertTrue(p.allows("http://127.0.0.1/status"));
        assertTrue(p.allows("http://127.0.0.1:8781/manifest.json"));
        assertTrue(p.allows("http://localhost:8781/x"));
        assertTrue(p.allows("http://[::1]:8781/x"));
        assertFalse(p.allows("http://example.com/x"));
        assertFalse(p.allows("http://127.0.0.1.evil.com/x"));
        assertFalse(p.allows("http://user@127.0.0.1/x"));
        assertFalse(p.allows("http://192.168.1.5/x"));
        assertFalse(p.allows("https://127.0.0.1/x"));
        assertTrue(HttpUtils.secure("http://127.0.0.1:8781/x").startsWith("http://"));
        assertTrue(HttpUtils.secure("http://example.com/x").startsWith("https://"));
    }
}
