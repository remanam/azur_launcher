package com.azur.launcher.profile;

import com.azur.launcher.config.AppDirs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** One instance per server under {@code <AppDirs>/instances/<serverId>/}; the pin comes from {@code profile.json}. */
public final class InstanceStore {

    private static final String BUNDLED = "/profile.json";

    private final AppDirs dirs;

    public InstanceStore(AppDirs dirs) {
        this.dirs = dirs;
    }

    /** Loads the instance of {@code serverId}; the profile file must exist and be valid. */
    public Instance load(String serverId) throws IOException {
        Path dir = dirs.instancesDir().resolve(serverId).normalize();
        if (!dir.startsWith(dirs.instancesDir()) || dir.equals(dirs.instancesDir())) {
            throw new ProfileException("Invalid server id: " + serverId);
        }
        Profile profile = ProfileLoader.load(dir.resolve("profile.json"));
        if (!profile.serverId().equals(serverId)) {
            throw new ProfileException("profile.json of " + serverId + " names server " + profile.serverId());
        }
        return new Instance(dir, profile);
    }

    /** The profile shipped inside the launcher jar. */
    public static Profile bundledProfile() throws IOException {
        try (InputStream in = InstanceStore.class.getResourceAsStream(BUNDLED)) {
            if (in == null) {
                throw new ProfileException("Bundled profile.json is missing");
            }
            return ProfileLoader.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), "bundled profile.json");
        }
    }

    /**
     * Instance for a profile derived from the signed server list. The list is the authority, so a changed pin
     * replaces the file on disk (the installed state then differs and the launcher installs or repairs).
     */
    public Instance ensure(Profile profile) throws IOException {
        Path dir = dirs.instancesDir().resolve(profile.serverId()).normalize();
        if (!dir.startsWith(dirs.instancesDir()) || dir.equals(dirs.instancesDir())) {
            throw new ProfileException("Invalid server id: " + profile.serverId());
        }
        String json = ProfileLoader.toJson(profile);
        Path file = dir.resolve("profile.json");
        if (!Files.exists(file) || !json.equals(Files.readString(file, StandardCharsets.UTF_8))) {
            Files.createDirectories(dir);
            Files.writeString(file, json, StandardCharsets.UTF_8);
        }
        return load(profile.serverId());
    }

    /** The bundled default profile is copied on first run; an existing (possibly updated) file is never overwritten. */
    public Instance loadDefault() throws IOException {
        String json;
        try (InputStream in = InstanceStore.class.getResourceAsStream(BUNDLED)) {
            if (in == null) {
                throw new ProfileException("Bundled profile.json is missing");
            }
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Profile bundled = ProfileLoader.parse(json, "bundled profile.json");
        Path file = dirs.instancesDir().resolve(bundled.serverId()).resolve("profile.json");
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json, StandardCharsets.UTF_8);
        }
        return load(bundled.serverId());
    }
}
