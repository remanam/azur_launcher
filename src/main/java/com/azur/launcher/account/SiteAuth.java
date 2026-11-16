package com.azur.launcher.account;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Account sign-in through the site (password over https, or the device flow with a code approved in the cabinet), the device key and the one-time login ticket.
 * Pure logic over {@link SiteApi} and {@link SessionStorage}; the clock and the pause between polls are injected for tests.
 */
public final class SiteAuth {

    /** The key is replaced after this long. */
    public static final long ROTATE_AFTER_MS = 30L * 24 * 3600 * 1000;
    /** A failed or unsupported rotation is retried at most this often. */
    public static final long ROTATE_RETRY_MS = 24L * 3600 * 1000;

    /** A started sign-in: show {@code started().userCode()} and open {@code started().verifyUrl()}. */
    public record Pending(SiteApi.Started started, DeviceKey key, String deviceName, long startedAt) {
    }

    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final SiteApi api;
    private final SessionStorage storage;
    private final LongSupplier clock;
    private final Sleeper sleeper;

    public SiteAuth(SiteApi api, SessionStorage storage, LongSupplier clock, Sleeper sleeper) {
        this.api = api;
        this.storage = storage;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public Optional<SiteSession> session() {
        return storage.read();
    }

    /** New key + device request. The private key stays in memory until the site approves. */
    public Pending begin(String deviceName) throws SiteException {
        DeviceKey key = DeviceKey.generate();
        return new Pending(api.start(deviceName, key.publicKeyBase64Url()), key, deviceName, clock.getAsLong());
    }

    /**
     * Password sign-in: a new device key, {@code device/login}, the session is stored. The password is wiped here whatever happens;
     * nothing about it is kept, stored or logged. Throws {@link SiteException} (bad_credentials, rate_limited, not_verified, ...).
     */
    public SiteSession login(String login, char[] password, String deviceName, String lang) throws SiteException, IOException {
        try {
            DeviceKey key = DeviceKey.generate();
            SiteApi.Polled r = api.login(login.trim(), password, deviceName, key.publicKeyBase64Url(), lang);
            SiteSession s = new SiteSession(r.deviceId(), r.refresh(), r.nick(), key.seedBase64Url(), key.publicKeyBase64Url(),
                    deviceName, clock.getAsLong(), 0);
            storage.write(s);
            return s;
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    /**
     * Polls until the player approves the code on the site, then stores the session. Returns empty when {@code cancel} is set.
     * Throws {@link SiteException} (code {@code expired} when the code ran out). A pause between polls = the site's interval.
     */
    public Optional<SiteSession> await(Pending p, AtomicBoolean cancel, Consumer<SiteSession> onDone) throws SiteException, IOException {
        long deadline = p.startedAt() + p.started().expiresIn() * 1000L;
        long pause = Math.max(2, p.started().interval()) * 1000L;
        while (!cancel.get()) {
            if (clock.getAsLong() > deadline) {
                throw new SiteException(400, "expired");
            }
            SiteApi.Polled r = pollQuietly(p);
            if (Objects.nonNull(r) && r.approved()) {
                SiteSession s = new SiteSession(r.deviceId(), r.refresh(), r.nick(), p.key().seedBase64Url(),
                        p.key().publicKeyBase64Url(), p.deviceName(), clock.getAsLong(), 0);
                storage.write(s);
                onDone.accept(s);
                return Optional.of(s);
            }
            try {
                sleeper.sleep(pause);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    /** One lost poll (a blip in the network) does not end the sign-in; only a site answer does. */
    private SiteApi.Polled pollQuietly(Pending p) throws SiteException {
        try {
            return api.poll(p.started().deviceCode());
        } catch (SiteException e) {
            if (e.network()) {
                return null;
            }
            throw e;
        }
    }

    /**
     * A one-time ticket for the game start. Rotates the key first when it is older than 30 days (best effort).
     * A refused refresh token (device revoked on the site, signed out everywhere) drops the saved sign-in.
     */
    public String ticket(String server) throws SiteException, IOException {
        SiteSession s = storage.read().orElseThrow(() -> new SiteException(401, "unauthorized"));
        s = rotateIfDue(s);
        try {
            SiteApi.Ticket t = api.ticket(s.deviceId(), s.refresh(), Objects.isNull(server) ? "" : server);
            storage.write(s.withRefresh(t.refresh()));
            return t.ticket();
        } catch (SiteException e) {
            if (SiteErrors.dropsSession(e)) {
                storage.clear();
            }
            throw e;
        }
    }

    /** Message the OLD key signs to move the device to a new key. */
    public static byte[] rotateMessage(String deviceId, String newPublicKey) {
        return ("azur-rotate-v1\0" + deviceId + "\0" + newPublicKey).getBytes(StandardCharsets.UTF_8);
    }

    /** True when the key is due for rotation and a retry is allowed. */
    public boolean rotationDue(SiteSession s) {
        long now = clock.getAsLong();
        return now - s.keyCreated() >= ROTATE_AFTER_MS && now - s.rotateTried() >= ROTATE_RETRY_MS;
    }

    SiteSession rotateIfDue(SiteSession s) throws IOException {
        if (!rotationDue(s)) {
            return s;
        }
        DeviceKey fresh = DeviceKey.generate();
        String proof = Base64.getUrlEncoder().withoutPadding().encodeToString(
                s.key().sign(rotateMessage(s.deviceId(), fresh.publicKeyBase64Url())));
        try {
            String refresh = api.rotate(s.deviceId(), s.refresh(), fresh.publicKeyBase64Url(), proof);
            SiteSession rotated = s.withKey(fresh, refresh.isEmpty() ? s.refresh() : refresh, clock.getAsLong());
            storage.write(rotated);
            return rotated;
        } catch (SiteException e) {
            SiteSession later = s.withRotateTried(clock.getAsLong());
            storage.write(later); // unsupported site or no network: keep the working key, try again tomorrow
            return later;
        }
    }

    /** Forget this device locally (the key and the refresh token are deleted). The cabinet still lists it until it is revoked there. */
    public void signOut() throws IOException {
        storage.clear();
    }
}
