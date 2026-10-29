package com.azur.launcher.game;

import com.azur.launcher.auth.AuthAccount;

/**
 * Session data the game needs, supplied by the account layer. The download/launch code knows nothing else
 * about accounts.
 */
public interface LaunchContext {

    String username();

    String uuid();

    String accessToken();

    String userType();

    default String clientId() {
        return "";
    }

    default String xuid() {
        return "";
    }

    default String userProperties() {
        return "{}";
    }

    /** Adapter for the existing account type. */
    static LaunchContext from(AuthAccount account) {
        return new LaunchContext() {
            @Override
            public String username() {
                return account.getUsername();
            }

            @Override
            public String uuid() {
                return account.getUuid();
            }

            @Override
            public String accessToken() {
                return account.getAccessToken();
            }

            @Override
            public String userType() {
                return account.getUserType();
            }

            @Override
            public String clientId() {
                return account.getClientId();
            }

            @Override
            public String xuid() {
                return account.getXuid();
            }

            @Override
            public String userProperties() {
                return account.getUserProperties();
            }
        };
    }
}
