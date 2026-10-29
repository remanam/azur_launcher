package com.azur.launcher.auth;

import java.util.Optional;

/** Finds the debug-only {@link AccountProvider}; empty in the release build, where its class does not exist. */
public final class DebugAccounts {

    private static final String PROVIDER_CLASS = "com.azur.launcher.debug.DebugAccountProvider";

    private DebugAccounts() {
    }

    public static Optional<AccountProvider> provider() {
        try {
            return Optional.of((AccountProvider) Class.forName(PROVIDER_CLASS)
                    .getDeclaredConstructor().newInstance());
        } catch (ReflectiveOperationException e) {
            return Optional.empty();
        }
    }
}
