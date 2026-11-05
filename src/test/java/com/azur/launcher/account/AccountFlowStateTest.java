package com.azur.launcher.account;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class AccountFlowStateTest {

    @Test
    void signedOutOffersTheButtonAndKeepsTheError() {
        AccountFlowState s = AccountFlowState.signedOut("account.err.network");
        assertTrue(s.canStart());
        assertFalse(s.showsCode());
        assertEquals("account.err.network", s.errorKey());
        assertFalse(AccountFlowState.starting().canStart(), "no double start");
    }

    @Test
    void waitingCountsDownAndNeverGoesNegative() throws Exception {
        FakeSite site = new FakeSite();
        SiteAuth auth = new SiteAuth(site, FakeSite.memory(), () -> 10_000L, ms -> { });
        AccountFlowState s = AccountFlowState.waiting(auth.begin("PC"));
        assertTrue(s.showsCode());
        assertFalse(s.canStart());
        assertEquals(600, s.secondsLeft(10_000L));
        assertEquals(1, s.secondsLeft(10_000L + 599_001L));
        assertEquals(0, s.secondsLeft(10_000L + 700_000L));
    }

    @Test
    void signedInShowsNickKeyAgeAndFingerprint() throws Exception {
        FakeSite site = new FakeSite();
        SiteAuth auth = new SiteAuth(site, FakeSite.memory(), () -> 1000L, ms -> { });
        SiteSession session = auth.await(auth.begin("PC"), new AtomicBoolean(), x -> { }).orElseThrow();
        AccountFlowState s = AccountFlowState.of(Optional.of(session));
        assertTrue(s.signedIn());
        assertEquals("Art", s.nick());
        assertEquals(session.key().fingerprint(), s.fingerprint());
        assertEquals(3, s.keyAgeDays(1000L + 3L * 24 * 3600 * 1000 + 5));
        assertFalse(AccountFlowState.of(Optional.empty()).signedIn());
    }
}
