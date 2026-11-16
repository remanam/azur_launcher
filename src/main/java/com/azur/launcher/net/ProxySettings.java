package com.azur.launcher.net;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** The persisted download proxy (host and port only; user and password live in memory, see {@link ProxyConfig}). */
public final class ProxySettings {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private transient Path file;
    private String proxyHost = "";
    private int proxyPort;

    /** @param legacyFile the file of earlier versions ({@code auth-settings.json}), read when {@code file} is absent */
    public ProxySettings(Path file, Path legacyFile) {
        this.file = file;
        load(Files.isRegularFile(file) ? file : legacyFile);
    }

    private void load(Path source) {
        try {
            if (Objects.nonNull(source) && Files.isRegularFile(source)) {
                ProxySettings loaded = GSON.fromJson(Files.readString(source, StandardCharsets.UTF_8), ProxySettings.class);
                if (Objects.nonNull(loaded)) {
                    proxyHost = nz(loaded.proxyHost);
                    proxyPort = loaded.proxyPort;
                }
            }
        } catch (Exception ignored) {
            // a broken settings file means defaults; the user can re-enter the values
        }
    }

    public synchronized void save() throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
    }

    public synchronized String proxyHost() {
        return proxyHost;
    }

    public synchronized int proxyPort() {
        return proxyPort;
    }

    public synchronized void setProxy(String host, int port) {
        proxyHost = nz(host).trim();
        proxyPort = port;
    }

    private static String nz(String s) {
        return Objects.isNull(s) ? "" : s;
    }
}
