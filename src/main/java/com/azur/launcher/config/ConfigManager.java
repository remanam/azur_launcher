package com.azur.launcher.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Reads and writes {@code config.json} in the launcher root. */
public final class ConfigManager {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final Path configFile;

    public ConfigManager(AppDirs dirs) {
        this.configFile = dirs.configFile();
    }

    public Path getConfigFile() {
        return configFile;
    }

    /** Returns the saved config, or defaults when the file is missing or unreadable. */
    public LauncherConfig load() {
        LauncherConfig cfg = null;
        try {
            if (Files.exists(configFile)) {
                cfg = GSON.fromJson(Files.readString(configFile, StandardCharsets.UTF_8), LauncherConfig.class);
            }
        } catch (Exception ignored) {
            // a corrupt file falls back to defaults
        }
        return Objects.isNull(cfg) ? new LauncherConfig() : cfg;
    }

    public void save(LauncherConfig config) throws IOException {
        Path parent = configFile.getParent();
        if (Objects.nonNull(parent)) {
            Files.createDirectories(parent);
        }
        Files.writeString(configFile, GSON.toJson(config), StandardCharsets.UTF_8);
    }
}
