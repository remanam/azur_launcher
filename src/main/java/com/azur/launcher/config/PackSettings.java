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

    /** Release address of the signed servers.json, used when the setting is empty (dev runs keep it empty). */
    public static final String DEFAULT_SERVERS_URL = Branding.serversUrl();

    /** Address of the signed servers.json (https). Empty: the release default, or only the cache in a dev run. */
    private String serversUrl = "";
    /** Address of the community API (https): registration, roles. Empty: the feature is off. */
    private String apiBase = "";

    public String getServersUrl() {
        if (serversUrl != null && !serversUrl.isBlank()) {
            return serversUrl;
        }
        boolean dev = !System.getProperty(com.azur.launcher.config.AppDirs.HOME_PROPERTY, "").isBlank();
        return dev ? "" : DEFAULT_SERVERS_URL;
    }

    public void setServersUrl(String serversUrl) {
        this.serversUrl = serversUrl.trim();
    }

    public String getApiBase() {
        return apiBase == null ? "" : apiBase;
    }

    public void setApiBase(String apiBase) {
        this.apiBase = apiBase.trim();
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
