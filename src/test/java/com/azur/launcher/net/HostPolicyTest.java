package com.azur.launcher.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void mirrorMustBeHttpsAndIsAllowedExplicitly() {
        HostPolicy p = HostPolicy.withMirror("https://mirror.azur.example/pack/");
        assertTrue(p.allows("https://mirror.azur.example/pack/a"));
        assertFalse(p.allows("https://other.example/a"));
        assertThrows(IllegalArgumentException.class, () -> HostPolicy.withMirror("http://mirror.azur.example/"));
        assertFalse(HostPolicy.withMirror("").allows("https://mirror.azur.example/a"));
    }
}
