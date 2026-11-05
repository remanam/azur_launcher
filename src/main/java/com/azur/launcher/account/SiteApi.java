package com.azur.launcher.account;

/** The launcher half of the site API (site `/api/launcher/*`). No passwords: the site approves a code in the browser. */
public interface SiteApi {

    record Started(String deviceCode, String userCode, String fingerprint, String verifyUrl, int expiresIn, int interval) {
    }

    /** {@code approved == false} = still waiting. */
    record Polled(boolean approved, String deviceId, String refresh, String nick) {
    }

    record Ticket(String ticket, String nick, String refresh) {
    }

    Started start(String deviceName, String publicKeyBase64Url) throws SiteException;

    Polled poll(String deviceCode) throws SiteException;

    /** Exchanges the refresh token for a one-time ticket (60 s) and a new refresh token. {@code server} may be empty (site default). */
    Ticket ticket(String deviceId, String refresh, String server) throws SiteException;

    /**
     * Replaces the device key; the proof is a signature of the OLD key over {@link SiteAuth#rotateMessage}.
     * Returns the new refresh token. A site without this endpoint answers 404 and the launcher keeps the old key.
     */
    String rotate(String deviceId, String refresh, String newPublicKeyBase64Url, String proofBase64Url) throws SiteException;
}
