package com.azur.launcher.account;

/**
 * What the account block shows. Pure data with the rules for the buttons and the countdown, so the window code has no logic
 * of its own (same idea as FeedState / HomeState).
 *
 * @param errorKey i18n key of the last failure, or null
 */
public record AccountFlowState(Phase phase, String errorKey, String userCode, String verifyUrl, String fingerprint,
                               long expiresAt, String nick, String deviceName, long keyCreated) {

    public enum Phase { SIGNED_OUT, STARTING, WAITING, SIGNED_IN }

    public static AccountFlowState signedOut(String errorKey) {
        return new AccountFlowState(Phase.SIGNED_OUT, errorKey, "", "", "", 0, "", "", 0);
    }

    public static AccountFlowState starting() {
        return new AccountFlowState(Phase.STARTING, null, "", "", "", 0, "", "", 0);
    }

    public static AccountFlowState waiting(SiteAuth.Pending p) {
        return new AccountFlowState(Phase.WAITING, null, p.started().userCode(), p.started().verifyUrl(),
                p.started().fingerprint(), p.startedAt() + p.started().expiresIn() * 1000L, "", p.deviceName(), 0);
    }

    public static AccountFlowState signedIn(SiteSession s) {
        return new AccountFlowState(Phase.SIGNED_IN, null, "", "", s.key().fingerprint(), 0, s.nick(), s.deviceName(), s.keyCreated());
    }

    public static AccountFlowState of(java.util.Optional<SiteSession> session) {
        return session.map(AccountFlowState::signedIn).orElseGet(() -> signedOut(null));
    }

    /** «Войти через сайт» works only when no sign-in is running. */
    public boolean canStart() {
        return phase == Phase.SIGNED_OUT;
    }

    public boolean showsCode() {
        return phase == Phase.WAITING;
    }

    public boolean signedIn() {
        return phase == Phase.SIGNED_IN;
    }

    /** Whole seconds until the code runs out, never negative. */
    public long secondsLeft(long now) {
        return phase == Phase.WAITING ? Math.max(0, (expiresAt - now + 999) / 1000) : 0;
    }

    /** Whole days since the key was made. */
    public long keyAgeDays(long now) {
        return keyCreated <= 0 ? 0 : Math.max(0, (now - keyCreated) / (24L * 3600 * 1000));
    }
}
