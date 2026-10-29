package com.azur.launcher.auth;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public class OfflineAccount implements AuthAccount {

    private final String username;
    private final String uuid;
    private final String accessToken;
    private final String userType;

    public OfflineAccount(String username, String uuid, String accessToken, String userType) {
        this.username = username;
        this.uuid = uuid;
        this.accessToken = accessToken;
        this.userType = userType;
    }

    public static OfflineAccount of(String username) {
        String name = (username == null || username.isBlank()) ? "Player" : username.trim();
        return new OfflineAccount(name, offlineUuid(name), "0", "legacy");
    }

    public static OfflineAccount of(String username, String uuid) {
        if (uuid == null || uuid.isBlank()) {
            return of(username);
        }
        return new OfflineAccount(username, uuid.replace("-", ""), "0", "legacy");
    }

    public static String offlineUuid(String username) {
        UUID u = UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        return u.toString().replace("-", "");
    }

    @Override
    public String getUsername() {
        return username;
    }

    @Override
    public String getUuid() {
        if (uuid.length() == 32) {
            return uuid.substring(0, 8) + "-" + uuid.substring(8, 12) + "-" + uuid.substring(12, 16)
                    + "-" + uuid.substring(16, 20) + "-" + uuid.substring(20);
        }
        return uuid;
    }

    @Override
    public String getAccessToken() {
        return accessToken;
    }

    @Override
    public String getUserType() {
        return userType;
    }
}
