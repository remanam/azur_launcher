package com.azur.launcher.account;

import java.util.Objects;

/**
 * What the launcher keeps after the site approved this device. One JSON in the encrypted vault; never logged.
 *
 * @param deviceId   id the site gave the device
 * @param refresh    rotating refresh token (the site replaces it at every ticket)
 * @param nick       canonical account nick (exact case)
 * @param seed       private key seed, base64url
 * @param publicKey  public key, base64url
 * @param deviceName the name shown in the cabinet
 * @param keyCreated when the key was made (ms), the 30-day rotation clock
 * @param rotateTried last failed or skipped rotation attempt (ms), so it is tried once a day
 */
public record SiteSession(String deviceId, String refresh, String nick, String seed, String publicKey, String deviceName,
                          long keyCreated, long rotateTried) {

    public DeviceKey key() {
        return DeviceKey.restore(seed, publicKey);
    }

    public SiteSession withRefresh(String value) {
        return new SiteSession(deviceId, value, nick, seed, publicKey, deviceName, keyCreated, rotateTried);
    }

    public SiteSession withRotateTried(long value) {
        return new SiteSession(deviceId, refresh, nick, seed, publicKey, deviceName, keyCreated, value);
    }

    public SiteSession withKey(DeviceKey key, String newRefresh, long now) {
        return new SiteSession(deviceId, newRefresh, nick, key.seedBase64Url(), key.publicKeyBase64Url(), deviceName, now, 0);
    }

    public boolean complete() {
        return notBlank(deviceId) && notBlank(refresh) && notBlank(nick) && notBlank(seed) && notBlank(publicKey);
    }

    private static boolean notBlank(String s) {
        return Objects.nonNull(s) && !s.isBlank();
    }
}
