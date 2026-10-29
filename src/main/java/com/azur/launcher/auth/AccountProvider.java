package com.azur.launcher.auth;

/**
 * Plug-in point for accounts that do not come from Microsoft sign-in. The release build has no implementation;
 * the debug build supplies one (see {@link DebugAccounts}).
 */
public interface AccountProvider {

    /** Text of the button on the login screen. */
    String buttonLabel();

    /** Text of the banner shown in the window while such an account is active. */
    String banner();

    String defaultName();

    AuthAccount create(String name);
}
