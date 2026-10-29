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

    /** True only when the account may start the game right now (verified licence, usable token). */
    boolean launchPermitted();

    default boolean needsRefresh() {
        return false;
    }

    default void refresh(MicrosoftAuthService service) throws AuthException {
    }
}
