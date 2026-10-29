package com.azur.launcher.auth;

/**
 * Plug-in point for accounts that do not come from Microsoft sign-in. The release build has no implementation;
 * the debug build supplies one (see {@link DebugAccounts}).
 */
public interface AccountProvider {

    /** Text of the button on the login screen. */
    String buttonLabel();

    String defaultName();

    AuthAccount create(String name);
}
