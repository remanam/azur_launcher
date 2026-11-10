package com.azur.launcher.account;

/** Maps a failed site call to the i18n key of one polite sentence (docs/design/FORM_ERRORS.md: no codes, no paths, no stacks). */
public final class SiteErrors {

    private SiteErrors() {
    }

    public static String key(SiteException e) {
        if (e.network()) {
            return "account.err.network";
        }
        return switch (e.code()) {
            case "bad_device", "unauthorized" -> "account.err.revoked";
            case "bad_credentials" -> "account.err.bad_credentials";
            case "not_verified" -> "account.err.not_verified";
            case "too_many_devices" -> "account.err.too_many_devices";
            case "not_found" -> "account.err.password_off";
            case "banned" -> "account.err.banned";
            case "no_nick" -> "account.err.no_nick";
            case "rate_limited" -> "account.err.rate_limited";
            case "expired", "invalid_code" -> "account.err.expired";
            case "invalid_key", "bad_server" -> "account.err.generic";
            default -> e.status() >= 500 ? "account.err.network" : "account.err.generic";
        };
    }

    /** The refresh token is no longer good: the saved sign-in must be dropped and the player signs in again. */
    public static boolean dropsSession(SiteException e) {
        return !e.network() && ("bad_device".equals(e.code()) || "unauthorized".equals(e.code()));
    }
}
