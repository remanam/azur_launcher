package com.azur.launcher.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Sign-in settings kept next to the accounts: the Azure application (client) id and an optional HTTP proxy for
 * auth traffic. Client id lookup order: system property {@code azur.microsoft.clientId}, environment variable
 * {@code AZUR_MICROSOFT_CLIENT_ID}, the settings file. No id is embedded in the launcher.
 */
public final class AuthSettings {

    public static final String CLIENT_ID_PROPERTY = "azur.microsoft.clientId";
    public static final String CLIENT_ID_ENV = "AZUR_MICROSOFT_CLIENT_ID";
    private static final Pattern GUID =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private transient Path file;
    private String clientId = "";
    private String proxyHost = "";
    private int proxyPort;

    public AuthSettings(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        try {
            if (file != null && Files.isRegularFile(file)) {
                AuthSettings loaded = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), AuthSettings.class);
                if (loaded != null) {
                    clientId = nz(loaded.clientId);
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

    /** The configured client id, or an empty string when none is set. */
    public synchronized String clientId() {
        String fromProperty = nz(System.getProperty(CLIENT_ID_PROPERTY)).trim();
        if (!fromProperty.isEmpty()) {
            return fromProperty;
        }
        String fromEnv = nz(System.getenv(CLIENT_ID_ENV)).trim();
        return fromEnv.isEmpty() ? clientId.trim() : fromEnv;
    }

    public static boolean isValidClientId(String id) {
        return id != null && GUID.matcher(id.trim()).matches();
    }

    public synchronized void setClientId(String id) {
        clientId = nz(id).trim();
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
        return s == null ? "" : s;
    }
}
