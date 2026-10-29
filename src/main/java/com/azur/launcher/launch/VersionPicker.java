package com.azur.launcher.launch;

import com.azur.launcher.game.LocalVersion;
import com.azur.launcher.servers.ServerEntry;

import java.util.List;
import java.util.Optional;

/** Chooses the installed game version that belongs to a server (its NeoForge build). */
public final class VersionPicker {

    private VersionPicker() {
    }

    /**
     * The launchable version whose id contains the server's NeoForge build (the last one when several match);
     * without a build in the list entry, the {@code fallbackId} when it is installed.
     */
    public static Optional<LocalVersion> pick(List<LocalVersion> installed, ServerEntry server, String fallbackId) {
        LocalVersion match = null;
        if (!server.neoforge().isBlank()) {
            for (LocalVersion version : installed) {
                if (version.launchable() && version.id().contains(server.neoforge())) {
                    match = version;
                }
            }
            return Optional.ofNullable(match);
        }
        for (LocalVersion version : installed) {
            if (version.launchable() && version.id().equals(fallbackId)) {
                return Optional.of(version);
            }
        }
        return Optional.empty();
    }
}
