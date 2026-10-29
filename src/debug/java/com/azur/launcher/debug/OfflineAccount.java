package com.azur.launcher.debug;

import com.azur.launcher.auth.AuthAccount;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** DEBUG BUILD ONLY. An account with no Microsoft check, for work while the sign-in hosts are unreachable. */
public final class OfflineAccount implements AuthAccount {

    private final String username;
    private final String uuid;

    public OfflineAccount(String username) {
        this.username = username == null || username.isBlank() ? "Player" : username.trim();
        this.uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + this.username).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    @Override
    public String getUsername() {
        return username;
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
        return true;
    }
}
