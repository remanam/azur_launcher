package com.azur.launcher.account;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AccountControllerTest {
    private final FakeSite site = new FakeSite();
    private final SessionStorage storage = FakeSite.memory();
    private final List<AccountFlowState.Phase> phases = new ArrayList<>();
    private final List<String> opened = new ArrayList<>();
    private final List<Optional<SiteSession>> changes = new ArrayList<>();
    private final AccountController c = new AccountController(new SiteAuth(site, storage, () -> 0L, ms -> { }), Runnable::run,
            s -> phases.add(s.phase()), opened::add, () -> "PC", changes::add);

    @Test
    void signInOpensTheBrowserShowsTheCodeThenSignsIn() {
        c.load();
        c.signIn();
        assertEquals(List.of(AccountFlowState.Phase.SIGNED_OUT, AccountFlowState.Phase.STARTING, AccountFlowState.Phase.WAITING,
                AccountFlowState.Phase.SIGNED_IN), phases);
        assertEquals(List.of("https://example.test/link?code=ABCDEFGH"), opened);
        assertEquals("Art", c.state().nick());
        assertEquals(1, changes.size());
        assertTrue(changes.get(0).isPresent());
    }

    @Test
    void anExpiredCodeBringsTheButtonBackWithAMessage() {
        site.pendingPolls = 1_000_000;
        SiteAuth slowClock = new SiteAuth(site, storage, new java.util.function.LongSupplier() {
            long t;

            @Override
            public long getAsLong() {
                return t += 400_000;
            }
        }, ms -> { });
        AccountController c2 = new AccountController(slowClock, Runnable::run, s -> phases.add(s.phase()), opened::add, () -> "PC", changes::add);
        c2.signIn();
        assertEquals("account.err.expired", c2.state().errorKey());
        assertTrue(c2.state().canStart());
    }

    @Test
    void signOutAndRefusedSessionClearTheState() {
        c.signIn();
        c.signOut();
        assertTrue(c.state().canStart());
        assertTrue(storage.read().isEmpty());
        c.sessionRefused("account.err.revoked");
        assertEquals("account.err.revoked", c.state().errorKey());
        assertTrue(changes.get(changes.size() - 1).isEmpty());
    }

    @Test
    void aSecondClickWhileRunningDoesNothing() {
        site.pendingPolls = 0;
        c.signIn();
        int before = site.calls.size();
        c.signIn(); // already signed in: not "can start"
        assertEquals(before, site.calls.size());
    }
}
