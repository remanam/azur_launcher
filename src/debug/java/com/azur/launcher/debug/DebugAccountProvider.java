package com.azur.launcher.debug;

import com.azur.launcher.auth.AccountProvider;
import com.azur.launcher.auth.AuthAccount;

/** DEBUG BUILD ONLY. Plugs {@link OfflineAccount} into the login screen. */
public final class DebugAccountProvider implements AccountProvider {

    @Override
    public String buttonLabel() {
        return "Local test account";
    }

    @Override
    public String defaultName() {
        return "Dev";
    }

    @Override
    public AuthAccount create(String name) {
        return new OfflineAccount(name);
    }
}
