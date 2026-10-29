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
    /** Invite code {@code azur1.…} from the server owner; passed to the updater. */
    private String invite = "";
    /** Explicit path of the azur-updater executable; empty: data folder or the bundled build. */
    private String updaterPath = "";

    public String getServersUrl() {
        return serversUrl == null ? "" : serversUrl;
    }

    public void setServersUrl(String serversUrl) {
        this.serversUrl = serversUrl.trim();
    }

    public String getInvite() {
        return invite == null ? "" : invite;
    }

    public void setInvite(String invite) {
        this.invite = invite.trim();
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
