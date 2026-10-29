package com.azur.launcher.auth;

/** The sign-in service URLs. Only the production set is public; tests build their own against a local server. */
record AuthEndpoints(String deviceCode, String token, String xboxUser, String xsts,
                     String mcLogin, String mcEntitlements, String mcLicense, String mcProfile) {

    static final AuthEndpoints PRODUCTION = new AuthEndpoints(
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode",
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/token",
            "https://user.auth.xboxlive.com/user/authenticate",
            "https://xsts.auth.xboxlive.com/xsts/authorize",
            "https://api.minecraftservices.com/authentication/login_with_xbox",
            "https://api.minecraftservices.com/entitlements/mcstore",
            "https://api.minecraftservices.com/entitlements/license",
            "https://api.minecraftservices.com/minecraft/profile");

    /** All services under one base URL (a fake server in tests). */
    static AuthEndpoints under(String base) {
        return new AuthEndpoints(base + "/devicecode", base + "/token", base + "/xbl", base + "/xsts",
                base + "/mclogin", base + "/mcstore", base + "/license", base + "/profile");
    }
}
