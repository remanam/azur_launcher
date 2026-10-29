package com.azur.launcher.profile;

import java.nio.file.Path;

/** One server instance: its folder, pinned profile and working directories. */
public record Instance(Path dir, Profile profile) {

    public Path profileFile() {
        return dir.resolve("profile.json");
    }

    public Path installedFile() {
        return dir.resolve("installed.json");
    }

    /** Game working directory (mods, saves, config, logs of the game). */
    public Path gameDir() {
        return dir.resolve("minecraft");
    }

    public Path crashReportsDir() {
        return gameDir().resolve("crash-reports");
    }

    public InstalledState installed() {
        return InstalledState.read(installedFile());
    }
}
