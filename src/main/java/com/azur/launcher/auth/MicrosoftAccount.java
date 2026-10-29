package com.azur.launcher.auth;

import com.azur.launcher.i18n.I18n;

public class MicrosoftAccount implements AuthAccount {

    public static final long REFRESH_AHEAD_MS = 12L * 60L * 60L * 1000L;

    private String name = "";
    private String uuid = "";
    private String accessToken = "";
    private String refreshToken = "";
    private String xuid = "";
    private String clientId = "";
    private long notAfter = 0L;
    private String userProperties = "{}";

    public MicrosoftAccount() {
    }

    public MicrosoftAccount(String name, String uuid, String accessToken, String refreshToken,
                            String xuid, String clientId, long notAfter) {
        this.name = name == null ? "" : name;
        this.uuid = uuid == null ? "" : uuid;
        this.accessToken = accessToken == null ? "" : accessToken;
        this.refreshToken = refreshToken == null ? "" : refreshToken;
        this.xuid = xuid == null ? "" : xuid;
        this.clientId = clientId == null ? "" : clientId;
        this.notAfter = notAfter;
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
        return accessToken;
    }

    @Override
    public String getUserType() {
        return "msa";
    }

    @Override
    public String getXuid() {
        return xuid;
    }

    @Override
    public String getClientId() {
        return clientId;
    }

    @Override
    public String getUserProperties() {
        return (userProperties == null || userProperties.isBlank()) ? "{}" : userProperties;
    }

    @Override
    public boolean needsRefresh() {
        return notAfter <= System.currentTimeMillis() + REFRESH_AHEAD_MS;
    }

    public boolean isExpired() {
        return notAfter <= System.currentTimeMillis();
    }

    @Override
    public void refresh(MicrosoftAuthService service) throws AuthException {
        if (service == null) {
            throw new AuthException("no_service", I18n.tr("auth.no_service"));
        }
        MicrosoftAccount renewed = service.refresh(this);
        copyFrom(renewed);
    }

    public void copyFrom(MicrosoftAccount other) {
        if (other == null) {
            return;
        }
        this.name = blankOr(other.name, this.name);
        this.uuid = blankOr(other.uuid, this.uuid);
        this.accessToken = blankOr(other.accessToken, this.accessToken);
        this.refreshToken = blankOr(other.refreshToken, this.refreshToken);
        this.xuid = blankOr(other.xuid, this.xuid);
        this.clientId = blankOr(other.clientId, this.clientId);
        this.userProperties = blankOr(other.userProperties, this.userProperties);
        if (other.notAfter > 0L) {
            this.notAfter = other.notAfter;
        }
    }

    private static String blankOr(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    public long getNotAfter() {
        return notAfter;
    }

    public void setNotAfter(long notAfter) {
        this.notAfter = notAfter;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken == null ? "" : refreshToken;
    }

    public void setName(String name) {
        this.name = name == null ? "" : name;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid == null ? "" : uuid;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken == null ? "" : accessToken;
    }

    public void setXuid(String xuid) {
        this.xuid = xuid == null ? "" : xuid;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId == null ? "" : clientId;
    }

    public void setUserProperties(String userProperties) {
        this.userProperties = userProperties == null ? "" : userProperties;
    }

    @Override
    public String toString() {
        return "MicrosoftAccount{name=" + name + ", uuid=" + uuid + ", hasRefreshToken="
                + (refreshToken != null && !refreshToken.isBlank()) + ", notAfter=" + notAfter + "}";
    }
}
