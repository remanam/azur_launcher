package com.azur.launcher.auth;

import com.azur.launcher.i18n.I18n;

/**
 * Decides, before a launch, whether the stored session can be used as is, must be refreshed, or can be used
 * from the cache because the auth hosts cannot be reached (restricted networks). A launch is never allowed for
 * an account whose licence was not verified or whose token has expired.
 */
public final class SessionManager {

    public enum State {
        /** Cached token is fresh enough, no auth host was contacted. */
        VALID,
        /** Credentials were refreshed and the licence re-checked. */
        REFRESHED,
        /** Refresh was due but the auth hosts are unreachable; the cached token is still valid. */
        CACHED_UNREACHABLE
    }

    private SessionManager() {
    }

    public static State prepare(MicrosoftAccount account, MicrosoftAuthService service, String defaultClientId,
                                MicrosoftAuthService.StageListener listener) throws AuthException {
        if (account.getClientId().isBlank()) {
            account.setClientId(defaultClientId);
        }
        boolean verified = account.getOwnershipVerifiedAt() > 0L;
        if (verified && !account.needsRefresh()) {
            return State.VALID;
        }
        try {
            account.copyFrom(service.refresh(account, listener));
            return State.REFRESHED;
        } catch (AuthException e) {
            if (!isUnreachable(e.getCode())) {
                throw e;
            }
            if (!account.launchPermitted()) {
                throw new AuthException("unreachable_expired", I18n.tr(verified
                        ? "auth.unreachable_expired" : "auth.unreachable_unverified"), e);
            }
            return State.CACHED_UNREACHABLE;
        }
    }

    static boolean isUnreachable(String code) {
        return "network_error".equals(code) || "http_429".equals(code) || code.startsWith("http_5");
    }
}
