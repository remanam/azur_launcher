package com.azur.launcher.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.azur.launcher.config.AppDirs;
import com.azur.launcher.servers.ServerEntry;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerProfilesTest {

    private static ServerEntry entry(String neoforge, String sha) {
        return new ServerEntry("isles", "Isles", "h", 25565, "1.21.1", neoforge, sha, "21", "beta", "", false, "");
    }

    @Test
    void sameBuildTakesTheEmbeddedHash() throws IOException {
        Profile base = InstanceStore.bundledProfile();
        Profile p = ServerProfiles.derive(entry(base.neoforge(), ""), base);
        assertEquals("isles", p.serverId());
        assertEquals(base.neoforgeInstallerSha256(), p.neoforgeInstallerSha256());
        assertEquals("beta", p.packChannel());
        assertEquals(base.ramMaxMb(), p.ramMaxMb());
    }

    @Test
    void otherBuildNeedsItsOwnHash() throws IOException {
        Profile base = InstanceStore.bundledProfile();
        assertThrows(ProfileException.class, () -> ServerProfiles.derive(entry("21.1.270", ""), base));
        Profile p = ServerProfiles.derive(entry("21.1.270", "b".repeat(64)), base);
        assertEquals("neoforge-21.1.270", p.neoforgeVersionId());
    }

    @Test
    void ensureWritesAndRewritesOnChange(@TempDir Path tmp) throws IOException {
        InstanceStore store = new InstanceStore(AppDirs.at(tmp));
        Profile base = InstanceStore.bundledProfile();
        Instance first = store.ensure(ServerProfiles.derive(entry(base.neoforge(), ""), base));
        assertEquals("beta", first.profile().packChannel());
        Instance second = store.ensure(ServerProfiles.derive(entry("21.1.270", "c".repeat(64)), base));
        assertEquals("21.1.270", second.profile().neoforge());
        assertEquals("21.1.270", store.load("isles").profile().neoforge());
    }

    @Test
    void badServerIdIsRefused(@TempDir Path tmp) throws IOException {
        Profile base = InstanceStore.bundledProfile();
        ServerEntry bad = new ServerEntry("../x", "X", "h", 25565, "", "", "", "", "", "", false, "");
        assertThrows(ProfileException.class, () -> ServerProfiles.derive(bad, base));
    }
}
