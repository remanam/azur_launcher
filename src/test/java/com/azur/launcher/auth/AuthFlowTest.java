package com.azur.launcher.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

class AuthFlowTest {

    private static final String CLIENT = "00000000-0000-0000-0000-000000000001";

    private static MicrosoftAccount signIn(FakeAuthServer server) throws AuthException {
        MicrosoftAuthService service = server.service();
        return service.login(service.requestDeviceCode(CLIENT), CLIENT, null);
    }

    private static MicrosoftAccount account(long notAfter, long verifiedAt) {
        MicrosoftAccount a = new MicrosoftAccount("Alex", "uuid", "mc-token", "refresh", "42", CLIENT, notAfter);
        a.setOwnershipVerifiedAt(verifiedAt);
        return a;
    }

    @Test
    void successfulSignInVerifiesOwnershipAndAllowsLaunch() throws Exception {
        try (FakeAuthServer server = new FakeAuthServer()) {
            MicrosoftAccount a = signIn(server);
            assertEquals("Alex", a.getUsername());
            assertEquals("01234567-89ab-cdef-0123-456789abcdef", a.getUuid());
            assertEquals("refresh-2", a.getRefreshToken());
            assertTrue(a.getOwnershipVerifiedAt() > 0);
            assertTrue(a.launchPermitted());
        }
    }

    @Test
    void accountWithoutTheGameIsRejected() throws Exception {
        try (FakeAuthServer server = new FakeAuthServer()) {
            server.entitlementItems = "[]";
            assertEquals("no_entitlement", assertThrows(AuthException.class, () -> signIn(server)).getCode());
            server.entitlementItems = "[{\"name\":\"something_else\"}]";
            assertEquals("no_entitlement", assertThrows(AuthException.class, () -> signIn(server)).getCode());
        }
    }

    @Test
    void ownedGameWithoutProfileIsRejected() throws Exception {
        try (FakeAuthServer server = new FakeAuthServer()) {
            server.profileStatus = 404;
            assertEquals("no_profile", assertThrows(AuthException.class, () -> signIn(server)).getCode());
        }
    }

    @Test
    void missingClientIdGivesTheSetupInstruction() throws Exception {
        try (FakeAuthServer server = new FakeAuthServer()) {
            AuthException e = assertThrows(AuthException.class, () -> server.service().requestDeviceCode(" "));
            assertEquals("no_client_id", e.getCode());
            assertEquals(0, server.requests.get());
        }
    }

    @Test
    void freshCachedSessionLaunchesWithoutContactingAuthServers() throws Exception {
        try (FakeAuthServer server = new FakeAuthServer()) {
            MicrosoftAccount a = account(System.currentTimeMillis() + 20L * 3_600_000L, 1L);
            assertEquals(SessionManager.State.VALID, SessionManager.prepare(a, server.service(), CLIENT, null));
            assertEquals(0, server.requests.get());
            assertTrue(a.launchPermitted());
        }
    }

    @Test
    void dueSessionIsRefreshedWhenReachable() throws Exception {
        try (FakeAuthServer server = new FakeAuthServer()) {
            MicrosoftAccount a = account(System.currentTimeMillis() + 3_600_000L, 1L);
            assertEquals(SessionManager.State.REFRESHED, SessionManager.prepare(a, server.service(), CLIENT, null));
            assertEquals("refresh-2", a.getRefreshToken());
            assertTrue(a.getNotAfter() > System.currentTimeMillis() + 20L * 3_600_000L);
        }
    }

    @Test
    void unreachableHostsFallBackToAStillValidCachedToken() throws IOException, AuthException {
        MicrosoftAccount a = account(System.currentTimeMillis() + 3_600_000L, 1L);
        assertEquals(SessionManager.State.CACHED_UNREACHABLE,
                SessionManager.prepare(a, FakeAuthServer.unreachableService(), CLIENT, null));
        assertTrue(a.launchPermitted());
    }

    @Test
    void unreachableHostsAndExpiredTokenBlockTheLaunch() throws IOException {
        MicrosoftAccount a = account(System.currentTimeMillis() - 1_000L, 1L);
        AuthException e = assertThrows(AuthException.class,
                () -> SessionManager.prepare(a, FakeAuthServer.unreachableService(), CLIENT, null));
        assertEquals("unreachable_expired", e.getCode());
        assertFalse(a.launchPermitted());
    }

    @Test
    void neverVerifiedAccountCannotUseTheCache() throws IOException {
        MicrosoftAccount a = account(System.currentTimeMillis() + 3_600_000L, 0L);
        assertFalse(a.launchPermitted());
        AuthException e = assertThrows(AuthException.class,
                () -> SessionManager.prepare(a, FakeAuthServer.unreachableService(), CLIENT, null));
        assertEquals("unreachable_expired", e.getCode());
    }

    @Test
    void rejectedRefreshTokenIsNotTreatedAsUnreachable() throws Exception {
        try (FakeAuthServer server = new FakeAuthServer()) {
            server.refreshRejected = true;
            MicrosoftAccount a = account(System.currentTimeMillis() + 3_600_000L, 1L);
            AuthException e = assertThrows(AuthException.class,
                    () -> SessionManager.prepare(a, server.service(), CLIENT, null));
            assertEquals("invalid_grant", e.getCode());
        }
    }
}
