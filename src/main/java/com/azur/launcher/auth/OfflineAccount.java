package com.azur.launcher.auth;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Nickname-only account for servers running in offline mode (friends' server). It carries no token: the game gets
 * access token "0" and user type "legacy". The UUID is the one a vanilla offline-mode server computes for the nick.
 */
public final class OfflineAccount implements AuthAccount {

    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private String name = "";
    private String uuid = "";

    /** For Gson. */
    public OfflineAccount() {
    }

    private OfflineAccount(String name) {
        this.name = name;
        this.uuid = uuidFor(name);
    }

    public static boolean isValidName(String name) {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    /** @throws IllegalArgumentException when the nickname is not 3-16 characters of [A-Za-z0-9_] */
    public static OfflineAccount of(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (!isValidName(trimmed)) {
            throw new IllegalArgumentException("invalid nickname");
        }
        return new OfflineAccount(trimmed);
    }

    /** Same value as the server's {@code UUIDUtil.createOfflinePlayerUUID}. */
    public static String uuidFor(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString();
    }

    @Override
    public String getUsername() {
        return name;
    }

    @Override
    public String getUuid() {
        return uuid;
    }

    @Override
    public String getAccessToken() {
        return "0";
    }

    @Override
    public String getUserType() {
        return "legacy";
    }

    @Override
    public boolean launchPermitted() {
        return isValidName(name);
    }
}
