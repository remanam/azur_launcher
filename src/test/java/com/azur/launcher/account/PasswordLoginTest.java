package com.azur.launcher.account;

import static org.junit.jupiter.api.Assertions.*;

import com.azur.launcher.config.LauncherConfig;
import com.google.gson.Gson;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Password sign-in logic without a window: states, session, errors, wiped password, and the flag defaults. */
class PasswordLoginTest {
    private final FakeSite site = new FakeSite();
    private final SessionStorage storage = FakeSite.memory();
    private final List<AccountFlowState> states = new ArrayList<>();
    private final List<Optional<SiteSession>> changes = new ArrayList<>();
    private final AccountController c = new AccountController(new SiteAuth(site, storage, () -> 0L, ms -> { }), Runnable::run,
            states::add, u -> { }, () -> "PC", changes::add);

    @Test
    void successStoresTheSessionWithTheDeviceKeyOnlyAndWipesThePassword() {
        char[] pw = "Longpass1!".toCharArray();
        c.signInWithPassword("art", pw, "ru");
        assertEquals("Longpass1!", site.lastLoginPassword);
        assertTrue(c.state().signedIn());
        assertEquals("Art", c.state().nick());
        assertArrayEquals(new char[pw.length], pw);
        SiteSession s = storage.read().orElseThrow();
        assertEquals("refresh-pw", s.refresh());
        assertFalse(s.toString().contains("Longpass1!"));
        assertEquals(1, changes.size());
        assertEquals(site.lastPubkey, s.key().publicKeyBase64Url());
    }

    @Test
    void errorsMapToPoliteSentencesAndKeepTheFormOpen() {
        String[][] cases = {{"bad_credentials", "account.err.bad_credentials"}, {"rate_limited", "account.err.rate_limited"},
                {"not_verified", "account.err.not_verified"}, {"too_many_devices", "account.err.too_many_devices"}, {"banned", "account.err.banned"},
                {"not_found", "account.err.password_off"}};
        for (String[] k : cases) {
            site.loginError = new SiteException(401, k[0]);
            char[] pw = "x".toCharArray();
            c.signInWithPassword("art", pw, "en");
            assertEquals(AccountFlowState.Phase.SIGNED_OUT, c.state().phase());
            assertEquals(k[1], c.state().errorKey(), k[0]);
            assertTrue(c.state().canStart());
            assertArrayEquals(new char[1], pw);
        }
        site.loginError = new SiteException("down", null);
        c.signInWithPassword("art", "x".toCharArray(), "en");
        assertEquals("account.err.network", c.state().errorKey());
        assertTrue(storage.read().isEmpty());
        assertTrue(changes.isEmpty());
    }

    @Test
    void aSecondClickWhileSignedInDoesNothingButWipes() {
        c.signInWithPassword("art", "a".toCharArray(), "ru");
        char[] again = "b".toCharArray();
        c.signInWithPassword("art", again, "ru");
        assertEquals(1, site.calls.stream().filter("login"::equals).count());
        assertArrayEquals(new char[1], again);
    }

    @Test
    void flagsDefaultToNoAnonymousPlayAndCodeLoginOn() {
        LauncherConfig fresh = new Gson().fromJson("{}", LauncherConfig.class);
        assertFalse(fresh.isAllowAnonymous());
        assertTrue(fresh.isAllowCodeLogin());
        LauncherConfig set = new Gson().fromJson("{\"allow_anonymous\":true,\"allow_code_login\":false}", LauncherConfig.class);
        assertTrue(set.isAllowAnonymous());
        assertFalse(set.isAllowCodeLogin());
        assertTrue(new Gson().toJson(new LauncherConfig()).contains("\"allow_anonymous\":false"));
    }
}
