package com.azur.launcher.game;

import java.nio.file.Path;

/** A version folder found under {@code <gameDir>/versions}. */
public record LocalVersion(
        String id,
        Path dir,
        boolean hasJar,
        boolean jsonValid,
        String type,
        String inheritsFrom) {

    public enum Status { READY, DAMAGED, MISSING_JAR }

    public boolean inheriting() {
        return inheritsFrom != null && !inheritsFrom.isBlank();
    }

    /** Loader versions (NeoForge) inherit the client jar from the vanilla version. */
    public Status status() {
        if (!jsonValid) {
            return Status.DAMAGED;
        }
        return hasJar || inheriting() ? Status.READY : Status.MISSING_JAR;
    }

    public boolean launchable() {
        return status() == Status.READY;
    }
}
