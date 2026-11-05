package com.azur.launcher.account;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class SiteAuthTest {
    private final FakeSite site = new FakeSite();
    private final SessionStorage storage = FakeSite.memory();
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final SiteAuth auth = new SiteAuth(site, storage, now::get, ms -> now.addAndGet(ms));

    private SiteSession signIn() throws Exception {
        SiteAuth.Pending p = auth.begin("PC");
        return auth.await(p, new AtomicBoolean(), s -> { }).orElseThrow();
    }

    @Test
    void deviceFlowStoresTheKeyAndTheRefreshTokenWithoutAPassword() throws Exception {
        site.pendingPolls = 2;
        SiteSession s = signIn();
        assertEquals("Art", s.nick());
        assertEquals("dev1", s.deviceId());
        assertEquals(site.lastPubkey, s.publicKey(), "the key the site was told about is the stored one");
        assertEquals(3, site.calls.stream().filter("poll"::equals).count());
        assertTrue(storage.read().isPresent());
        assertEquals(1_000_000L + 2 * 3000L, s.keyCreated(), "slept the site interval between polls");
    }

    @Test
    void aLostPollDoesNotEndTheSignIn() throws Exception {
        site.pollError = new SiteException("blip", null);
        SiteAuth.Pending p = auth.begin("PC");
        AtomicBoolean cancel = new AtomicBoolean();
        SiteAuth slow = new SiteAuth(site, storage, now::get, ms -> {
            now.addAndGet(ms);
            if (now.get() > 1_000_000L + 7000) {
                site.pollError = null;
            }
        });
        assertTrue(slow.await(p, cancel, s -> { }).isPresent());
    }

    @Test
    void anExpiredCodeIsReportedAndNothingIsStored() throws Exception {
        site.pendingPolls = 1_000_000;
        SiteAuth.Pending p = auth.begin("PC");
        SiteException e = assertThrows(SiteException.class, () -> auth.await(p, new AtomicBoolean(), s -> { }));
        assertEquals("expired", e.code());
        assertEquals("account.err.expired", SiteErrors.key(e));
        assertTrue(storage.read().isEmpty());
    }

    @Test
    void cancelStopsPollingAndStoresNothing() throws Exception {
        site.pendingPolls = 1_000_000;
        SiteAuth.Pending p = auth.begin("PC");
        AtomicBoolean cancel = new AtomicBoolean();
        SiteAuth cancelling = new SiteAuth(site, storage, now::get, ms -> cancel.set(true));
        assertTrue(cancelling.await(p, cancel, s -> { }).isEmpty());
        assertTrue(storage.read().isEmpty());
    }

    @Test
    void ticketRotatesTheRefreshToken() throws Exception {
        signIn();
        assertEquals("ticket-1", auth.ticket(""));
        assertEquals("refresh-2", storage.read().orElseThrow().refresh());
        assertEquals("ticket-2", auth.ticket(""));
        assertTrue(site.calls.contains("ticket:refresh-2"));
    }

    @Test
    void aRevokedDeviceDropsTheSavedSignIn() throws Exception {
        signIn();
        site.ticketError = new SiteException(401, "bad_device");
        SiteException e = assertThrows(SiteException.class, () -> auth.ticket(""));
        assertEquals("account.err.revoked", SiteErrors.key(e));
        assertTrue(storage.read().isEmpty(), "key and refresh token are gone");
    }

    @Test
    void aLostNetworkKeepsTheSignIn() throws Exception {
        signIn();
        site.ticketError = new SiteException("down", null);
        SiteException e = assertThrows(SiteException.class, () -> auth.ticket(""));
        assertEquals("account.err.network", SiteErrors.key(e));
        assertTrue(storage.read().isPresent());
    }

    @Test
    void keyIsReplacedAfter30DaysWithAProofOfTheOldKey() throws Exception {
        SiteSession first = signIn();
        now.addAndGet(SiteAuth.ROTATE_AFTER_MS + 1);
        auth.ticket("");
        SiteSession s = storage.read().orElseThrow();
        assertNotEquals(first.publicKey(), s.publicKey());
        assertEquals(site.lastRotateKey, s.publicKey());
        byte[] proof = Base64.getUrlDecoder().decode(site.lastRotateProof);
        java.security.Signature v = java.security.Signature.getInstance("Ed25519");
        byte[] prefix = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};
        byte[] oldPub = Base64.getUrlDecoder().decode(first.publicKey());
        byte[] x509 = new byte[44];
        System.arraycopy(prefix, 0, x509, 0, 12);
        System.arraycopy(oldPub, 0, x509, 12, 32);
        v.initVerify(java.security.KeyFactory.getInstance("Ed25519").generatePublic(new java.security.spec.X509EncodedKeySpec(x509)));
        v.update(SiteAuth.rotateMessage("dev1", s.publicKey()));
        assertTrue(v.verify(proof), "the old key vouches for the new one");
        assertTrue(s.keyCreated() > first.keyCreated());
        assertTrue(site.calls.contains("ticket:refresh-rotated"), "the ticket uses the refresh token the rotation returned");
    }

    @Test
    void aSiteWithoutRotationKeepsTheOldKeyAndRetriesDaily() throws Exception {
        SiteSession first = signIn();
        site.rotateError = new SiteException(404, "");
        now.addAndGet(SiteAuth.ROTATE_AFTER_MS + 1);
        auth.ticket("");
        assertEquals(first.publicKey(), storage.read().orElseThrow().publicKey());
        auth.ticket("");
        assertEquals(1, site.calls.stream().filter("rotate"::equals).count(), "not retried at once");
        now.addAndGet(SiteAuth.ROTATE_RETRY_MS + 1);
        auth.ticket("");
        assertEquals(2, site.calls.stream().filter("rotate"::equals).count());
    }

    @Test
    void youngKeysAreNotRotated() throws Exception {
        signIn();
        now.addAndGet(SiteAuth.ROTATE_AFTER_MS - 1000);
        auth.ticket("");
        assertFalse(site.calls.contains("rotate"));
    }

    @Test
    void signOutDeletesEverything() throws Exception {
        signIn();
        auth.signOut();
        assertTrue(auth.session().isEmpty());
        assertThrows(SiteException.class, () -> auth.ticket(""));
    }

    @Test
    void errorTextsAreSentencesNotCodes() {
        for (String code : new String[] {"bad_device", "banned", "no_nick", "rate_limited", "expired", "whatever"}) {
            String key = SiteErrors.key(new SiteException(400, code));
            assertTrue(key.startsWith("account.err."), key);
        }
        assertEquals("account.err.network", SiteErrors.key(new SiteException(503, "")));
    }
}
