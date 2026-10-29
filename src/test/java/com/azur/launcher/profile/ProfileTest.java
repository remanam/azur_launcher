package com.azur.launcher.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azur.launcher.config.AppDirs;
import com.azur.launcher.game.GamePaths;
import com.azur.launcher.game.PinService;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileTest {

    private static final String SHA = "a".repeat(64);
    private static final String GOOD = """
            {"schema":1,"serverId":"azur-main","minecraft":"1.21.1","neoforge":"21.1.264",
             "neoforgeInstallerSha256":"%s","java":21,"jvmArgs":["-XX:+UseG1GC"],
             "ramMinMb":2048,"ramMaxMb":6144,"packChannel":"stable","mirrorBase":""}
            """.formatted(SHA);

    private static String problems(String json) {
        return assertThrows(ProfileException.class, () -> ProfileLoader.parse(json, "t")).getMessage();
    }

    @Test
    void validProfileLoads() throws IOException {
        Profile p = ProfileLoader.parse(GOOD, "t");
        assertEquals("neoforge-21.1.264", p.neoforgeVersionId());
        assertEquals(6144, p.ramMaxMb());
    }

    @Test
    void bundledProfileIsValid() throws IOException {
        assertEquals("1.21.1", InstanceStore.bundledProfile().minecraft());
    }

    @Test
    void rejectsBadValues() {
        assertTrue(problems(GOOD.replace("21.1.264", "20.4.1")).contains("does not belong"));
        assertTrue(problems(GOOD.replace(SHA, "zz")).contains("Sha256"));
        assertTrue(problems(GOOD.replace("\"java\":21", "\"java\":8")).contains("java"));
        assertTrue(problems(GOOD.replace("6144", "1024")).contains("ram"));
        assertTrue(problems(GOOD.replace("\"mirrorBase\":\"\"", "\"mirrorBase\":\"http://m.example\"")).contains("mirrorBase"));
        assertTrue(problems(GOOD.replace("-XX:+UseG1GC", "-javaagent:x.jar")).contains("controlled by the launcher"));
        assertTrue(problems(GOOD.replace("-XX:+UseG1GC", "-Xmx99g")).contains("controlled by the launcher"));
        assertTrue(problems(GOOD.replace("\"serverId\":\"azur-main\"", "\"serverId\":\"../x\"")).contains("serverId"));
        assertTrue(problems("{not json").contains("Cannot parse"));
    }

    @Test
    void storeCopiesBundledProfileOnceAndKeepsEdits(@TempDir Path dir) throws IOException {
        InstanceStore store = new InstanceStore(AppDirs.at(dir));
        Instance first = store.loadDefault();
        assertTrue(Files.exists(first.profileFile()));
        Files.writeString(first.profileFile(), Files.readString(first.profileFile()).replace("6144", "8192"));
        assertEquals(8192, store.loadDefault().profile().ramMaxMb());
        assertThrows(ProfileException.class, () -> store.load("../escape"));
    }

    @Test
    void pinCheckDetectsMissingAndMismatchedInstall(@TempDir Path dir) throws IOException {
        AppDirs dirs = AppDirs.at(dir);
        Instance inst = new InstanceStore(dirs).loadDefault();
        GamePaths paths = new GamePaths(dirs.gameDir());
        PinService pin = new PinService(dirs, paths, m -> { });
        assertThrows(PinService.PinMismatchException.class, () -> pin.requirePinned(inst));

        Profile p = inst.profile();
        new InstalledState(p.minecraft(), p.neoforge(), p.neoforgeInstallerSha256()).write(inst.installedFile());
        Path json = paths.versionJson(p.neoforgeVersionId());
        Files.createDirectories(json.getParent());
        Files.writeString(json, "{\"id\":\"x\",\"inheritsFrom\":\"1.21.1\"}");
        assertTrue(pin.check(inst).isEmpty());

        Files.writeString(json, "{\"id\":\"x\",\"inheritsFrom\":\"1.20.1\"}");
        assertEquals(1, pin.check(inst).size());
        new InstalledState(p.minecraft(), "21.1.100", p.neoforgeInstallerSha256()).write(inst.installedFile());
        assertEquals(2, pin.check(inst).size());
    }
}
