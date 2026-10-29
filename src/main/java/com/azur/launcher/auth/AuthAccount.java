package com.azur.launcher.auth;

public interface AuthAccount {

    String getUsername();

    String getUuid();

    String getAccessToken();

    String getUserType();

    default String getXuid() {
        return "";
    }

    default String getClientId() {
        return "";
    }

    default String getUserProperties() {
        return "{}";
    }

    default boolean needsRefresh() {
        return false;
    }

    default void refresh(MicrosoftAuthService service) throws AuthException {
    }
}
