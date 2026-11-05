package com.azur.launcher.account;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoginHandoffTest {
    @TempDir Path dir;
    private final FakeSite site = new FakeSite();
    private final SessionStorage storage = FakeSite.memory();
    private final SiteAuth auth = new SiteAuth(site, storage, () -> 5_000_000L, ms -> { });
    private final LoginHandoff handoff = new LoginHandoff(auth, "");
    private SiteSession session;

    @BeforeEach
    void signIn() throws Exception {
        session = auth.await(auth.begin("PC"), new AtomicBoolean(), s -> { }).orElseThrow();
    }

    private static Set<PosixFilePermission> perms(Path p) throws Exception {
        return Files.getPosixFilePermissions(p);
    }

    @Test
    void ticketAndKeyFilesAreOwnerOnlyAndTheCommandLineHoldsOnlyPaths() throws Exception {
        LoginHandoff.Prepared p = handoff.prepare(dir, true);
        Path ticket = dir.resolve(LoginHandoff.TICKET_FILE);
        Path key = dir.resolve(LoginHandoff.KEY_FILE);
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms(ticket));
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), perms(key));
        assertEquals("ticket-1", Files.readString(ticket));
        assertEquals("dev1\n" + session.seed() + "\n", Files.readString(key));
        assertEquals(List.of("-Dazur.ticketFile=" + ticket, "-Dazur.deviceKeyFile=" + key), p.jvmArgs());
        for (String arg : p.jvmArgs()) {
            assertFalse(arg.contains("ticket-1") || arg.contains(session.seed()) || arg.contains("refresh"), "no secret in an argument");
        }
        assertTrue(Files.size(key) <= 128 && Files.size(ticket) <= 128, "within the client limit");
    }

    @Test
    void keyStaysInTheLauncherWhenSharingIsOff() throws Exception {
        LoginHandoff.Prepared p = handoff.prepare(dir, false);
        assertEquals(1, p.jvmArgs().size());
        assertFalse(Files.exists(dir.resolve(LoginHandoff.KEY_FILE)));
    }

    @Test
    void anOldFileIsReplacedNotWidened() throws Exception {
        Path ticket = dir.resolve(LoginHandoff.TICKET_FILE);
        Files.writeString(ticket, "stale");
        Files.setPosixFilePermissions(ticket, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OTHERS_READ));
        handoff.prepare(dir, true);
        assertEquals("ticket-1", Files.readString(ticket));
        assertEquals(2, perms(ticket).size());
    }

    @Test
    void aLostNetworkStillHandsOverTheKey() throws Exception {
        site.ticketError = new SiteException("down", null);
        LoginHandoff.Prepared p = handoff.prepare(dir, true);
        assertEquals(1, p.jvmArgs().size());
        assertTrue(p.jvmArgs().get(0).startsWith("-Dazur.deviceKeyFile="));
        assertTrue(p.notice().contains("network"));
        assertFalse(Files.exists(dir.resolve(LoginHandoff.TICKET_FILE)));
    }

    @Test
    void aRevokedDeviceStopsTheLaunch() {
        site.ticketError = new SiteException(401, "bad_device");
        SiteException e = assertThrows(SiteException.class, () -> handoff.prepare(dir, true));
        assertEquals("account.err.revoked", SiteErrors.key(e));
        assertFalse(Files.exists(dir.resolve(LoginHandoff.KEY_FILE)), "no key is left for a revoked device");
    }

    @Test
    void notSignedInHandsOverNothing() throws Exception {
        auth.signOut();
        assertTrue(handoff.prepare(dir, true).jvmArgs().isEmpty());
    }

    @Test
    void cleanupRemovesWhatTheGameDidNotTake() throws Exception {
        handoff.prepare(dir, true);
        LoginHandoff.cleanup(dir);
        assertFalse(Files.exists(dir.resolve(LoginHandoff.TICKET_FILE)));
        assertFalse(Files.exists(dir.resolve(LoginHandoff.KEY_FILE)));
    }
}
