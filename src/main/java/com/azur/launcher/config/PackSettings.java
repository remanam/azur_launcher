package com.azur.launcher.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server list and pack sync settings: {@code <data>/pack.json}. Kept apart from {@link LauncherConfig} so the
 * game and account settings stay independent of the pack tooling.
 */
public final class PackSettings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Address of the signed servers.json (https). Empty: only the cached list is shown. */
    private String serversUrl = "";
    /** Only read from files written before the invite code moved into the vault; never written again. */
    private String invite;
    /** Explicit path of the azur-updater executable; empty: data folder or the bundled build. */
    private String updaterPath = "";

    public String getServersUrl() {
        return serversUrl == null ? "" : serversUrl;
    }

    public void setServersUrl(String serversUrl) {
        this.serversUrl = serversUrl.trim();
    }

    /** The plain invite of an old pack.json (migration); cleared once taken so that it is not saved again. */
    public String takeLegacyInvite() {
        String legacy = invite == null ? "" : invite.trim();
        invite = null;
        return legacy;
    }

    public String getUpdaterPath() {
        return updaterPath == null ? "" : updaterPath;
    }

    public void setUpdaterPath(String updaterPath) {
        this.updaterPath = updaterPath.trim();
    }

    public static PackSettings load(Path file) {
        try {
            PackSettings loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), PackSettings.class);
            return loaded == null ? new PackSettings() : loaded;
        } catch (IOException | RuntimeException e) {
            return new PackSettings();
        }
    }

    public void save(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
    }
}
