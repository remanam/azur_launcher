package com.azur.launcher.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.Test;

class AppDirsTest {

    private static final Path HOME = Path.of("/home/user");

    @Test
    void windowsUsesAppData() {
        Path root = AppDirs.resolveRoot("Windows 11", Path.of("C:/Users/u"), Map.of("APPDATA", "C:/Users/u/AppData/Roaming"));
        assertEquals(Path.of("C:/Users/u/AppData/Roaming/AzurLauncher"), root);
    }

    @Test
    void windowsWithoutAppDataFallsBackToRoaming() {
        Path root = AppDirs.resolveRoot("Windows 10", HOME, Map.of());
        assertEquals(HOME.resolve("AppData").resolve("Roaming").resolve("AzurLauncher"), root);
    }

    @Test
    void macUsesApplicationSupport() {
        Path root = AppDirs.resolveRoot("Mac OS X", HOME, Map.of());
        assertEquals(HOME.resolve("Library").resolve("Application Support").resolve("AzurLauncher"), root);
    }

    @Test
    void linuxHonoursXdgAndDefaultsToLocalShare() {
        assertEquals(Path.of("/data/xdg/azur-launcher"),
                AppDirs.resolveRoot("Linux", HOME, Map.of("XDG_DATA_HOME", "/data/xdg")));
        assertEquals(HOME.resolve(".local").resolve("share").resolve("azur-launcher"),
                AppDirs.resolveRoot("Linux", HOME, Map.of()));
    }

    @Test
    void derivedPathsLiveUnderTheRoot() {
        AppDirs dirs = AppDirs.at(Path.of("/tmp/azur-test"));
        Path root = dirs.root();
        assertEquals(root.resolve("config.json"), dirs.configFile());
        assertEquals(root.resolve("accounts").resolve("accounts.json"), dirs.accountsFile());
        assertEquals(root.resolve("game"), dirs.gameDir());
        assertEquals(root.resolve("logs"), dirs.logsDir());
    }

    @Test
    void homePropertyOverridesTheRoot() {
        String old = System.getProperty(AppDirs.HOME_PROPERTY);
        try {
            System.setProperty(AppDirs.HOME_PROPERTY, "/tmp/azur-override");
            assertEquals(Path.of("/tmp/azur-override").toAbsolutePath().normalize(), AppDirs.system().root());
        } finally {
            if (Objects.isNull(old)) {
                System.clearProperty(AppDirs.HOME_PROPERTY);
            } else {
                System.setProperty(AppDirs.HOME_PROPERTY, old);
            }
        }
    }
}
