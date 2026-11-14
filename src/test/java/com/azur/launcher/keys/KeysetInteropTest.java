package com.azur.launcher.keys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.azur.launcher.feed.FeedDocument;
import com.azur.launcher.pack.PackException;
import com.azur.launcher.pack.SignatureVerifier;
import com.azur.launcher.servers.ServerListException;
import com.azur.launcher.servers.ServerListVerifier;
import com.azur.launcher.update.UpdateException;
import com.azur.launcher.update.UpdateManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End to end with the real Go tools (tools/updater azur-servers + azur-dist) on TEMPORARY keys: three roots (A working,
 * B and C emergency) and three working keys (servers, pack, launcher) sign real documents (servers.json, feed.json, the
 * pack manifest, launcher.json) and the launcher's own Java code verifies them through the keyset; then the emergency
 * scenarios (A lost/stolen -> B or C alone, B lost -> C, C lost -> B). Skipped without Go. Same scenarios as
 * tools/updater/e2e.sh (Go side).
 */
class KeysetInteropTest {

    @TempDir
    static Path bin;
    private static Path servers;
    private static Path dist;

    @TempDir
    Path work;

    private RootKeys roots;
    private final Instant now = Instant.now();

    @BeforeAll
    static void buildTools() {
        servers = build("azur-servers");
        dist = build("azur-dist");
    }

    private static Path build(String cmd) {
        Path out = bin.resolve(cmd);
        try {
            Process p = new ProcessBuilder("go", "build", "-o", out.toString(), "./cmd/" + cmd)
                    .directory(Path.of("..", "tools", "updater").toFile()).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(180, TimeUnit.SECONDS) && p.exitValue() == 0 ? out : null;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    private int exec(Path tool, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(tool.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(work.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            throw new IOException("timeout: " + String.join(" ", args));
        }
        if (p.exitValue() != 0) {
            Files.writeString(work.resolve("last-error.txt"), out);
        }
        return p.exitValue();
    }

    private void run(String... args) throws Exception {
        if (exec(servers, args) != 0) {
            throw new IOException(String.join(" ", args) + ": " + Files.readString(work.resolve("last-error.txt")));
        }
    }

    private void runDist(String... args) throws Exception {
        if (exec(dist, args) != 0) {
            throw new IOException("azur-dist " + String.join(" ", args) + ": " + Files.readString(work.resolve("last-error.txt")));
        }
    }

    private boolean refusedByTool(String... args) throws Exception {
        return exec(servers, args) != 0;
    }

    private byte[] read(String file) throws IOException {
        return Files.readAllBytes(work.resolve(file));
    }

    private Keyset open(String file, Keyset prev) throws Exception {
        return Keyset.open(read(file), roots, prev, now);
    }

    private KeysetException.Reason refused(String file, Keyset prev) {
        return assertThrows(KeysetException.class, () -> open(file, prev)).reason();
    }

    private String pub(String name) throws IOException {
        return Files.readString(work.resolve("keys/" + name + ".pub")).trim();
    }

    /** Temp keys, root_keys.txt with three roots and keyset v1 signed by A with servers-1, pack-1, launcher-1. */
    private Keyset setup() throws Exception {
        assumeTrue(servers != null && dist != null, "Go toolchain not available");
        for (String r : List.of("root_a", "root_a2", "root_x")) {
            run("keygen", "--role", "root_working", "--out", "keys/" + r + ".key");
        }
        for (String r : List.of("root_b", "root_c", "root_b2", "root_c2")) {
            run("keygen", "--role", "root_emergency", "--out", "keys/" + r + ".key");
        }
        for (String r : List.of("servers", "pack", "launcher")) {
            run("keygen", "--role", r, "--out", "keys/" + r + ".key");
            run("keygen", "--role", r, "--out", "keys/" + r + "2.key");
        }
        String text = "working " + pub("root_a") + "\nemergency " + pub("root_b") + "\nemergency " + pub("root_c") + "\n";
        Files.writeString(work.resolve("roots.txt"), text);
        roots = RootKeys.parse(text);
        run("keyset", "new", "--src", "src.json");
        for (String r : List.of("servers", "pack", "launcher")) {
            run("keyset", "add-key", "--src", "src.json", "--role", r, "--pub", "keys/" + r + ".pub", "--id", r + "-1");
        }
        run("keyset", "sign", "--src", "src.json", "--root-key", "keys/root_a.key", "--roots", "roots.txt", "--out", "ks1.json");
        return open("ks1.json", null);
    }

    /** servers.json, feed.json, pack manifest and launcher.json signed by the given working keys (file suffix). */
    private void signDocuments(String suffix, String serversKey, String packKey, String launcherKey) throws Exception {
        Files.writeString(work.resolve("s.json"), "{\"schema\":2,\"validUntil\":\"2099-01-01T00:00:00Z\",\"servers\":[{\"id\":\"isles\","
                + "\"name\":\"Example\",\"address\":\"example.test\",\"pack\":{\"url\":\"https://example.test/pack\",\"key\":\""
                + pub("pack") + "\"}}]}");
        run("sign", "--key", "keys/" + serversKey + ".key", "--in", "s.json", "--out", "servers" + suffix + ".json");
        Files.createDirectories(work.resolve("feed" + suffix));
        runDist("feed", "add", "--data", "feed" + suffix, "--key", "keys/" + serversKey + ".key", "--id", "n1",
                "--title-ru", "Новость", "--text-ru", "Текст");
        runDist("feed", "publish", "--data", "feed" + suffix, "--key", "keys/" + serversKey + ".key");
        Path pack = work.resolve("pack");
        Files.createDirectories(pack.resolve("mods"));
        Files.createDirectories(pack.resolve("config"));
        Files.writeString(pack.resolve("mods/azur_core-1.jar"), "core1");
        Files.writeString(pack.resolve("config/azur.toml"), "c=1");
        Files.writeString(pack.resolve("pack.json"), "{\"pack_version\":\"1\",\"mc\":\"1.21.1\",\"neoforge\":\"21.1.228\"}");
        runDist("publish", "--data", "dist" + suffix, "--key", "keys/" + packKey + ".key", "--pack", pack.toString());
        Files.writeString(work.resolve("l.json"), "{\"version\":\"1.0.7\",\"sha256\":\"" + "0".repeat(64)
                + "\",\"size\":5,\"date\":\"2026-11-05\",\"url\":\"https://example.test/dl/azur-launcher.jar\","
                + "\"min_wrapper\":\"1.0.0\",\"required\":false,\"notes\":\"Исправления\"}");
        run("sign-launcher", "--no-legacy", "--add-key", "keys/" + launcherKey + ".key", "--in", "l.json",
                "--out", "launcher" + suffix + ".json");
    }

    private static List<PublicKey> jdk(List<byte[]> raw) {
        return Ed25519.publicKeys(raw);
    }

    private void assertServersOk(String suffix, Keyset ks, Instant at) throws Exception {
        ServerListVerifier.open(read("servers" + suffix + ".json"), jdk(ks.keys(Keyset.SERVERS, at)), at);
        FeedDocument.open(read("feed" + suffix + "/feed.json"), jdk(ks.keys(Keyset.SERVERS, at)));
    }

    private void assertServersRefused(String suffix, Keyset ks, Instant at) {
        assertThrows(ServerListException.class,
                () -> ServerListVerifier.open(read("servers" + suffix + ".json"), jdk(ks.keys(Keyset.SERVERS, at)), at));
        assertThrows(UpdateException.class,
                () -> FeedDocument.open(read("feed" + suffix + "/feed.json"), jdk(ks.keys(Keyset.SERVERS, at))));
    }

    private void assertPackOk(String suffix, Keyset ks, Instant at) throws Exception {
        assertEquals("1", SignatureVerifier.open(read("dist" + suffix + "/current.json"), ks.keys(Keyset.PACK, at)).packVersion());
    }

    private void assertPackRefused(String suffix, Keyset ks, Instant at) {
        assertThrows(PackException.class, () -> SignatureVerifier.open(read("dist" + suffix + "/current.json"), ks.keys(Keyset.PACK, at)));
    }

    private void assertLauncherOk(String suffix, Keyset ks, Instant at) throws Exception {
        assertEquals("1.0.7", UpdateManifest.openWithKeys(read("launcher" + suffix + ".json"), jdk(ks.keys(Keyset.LAUNCHER, at))).version());
    }

    private void assertLauncherRefused(String suffix, Keyset ks, Instant at) {
        assertThrows(UpdateException.class,
                () -> UpdateManifest.openWithKeys(read("launcher" + suffix + ".json"), jdk(ks.keys(Keyset.LAUNCHER, at))));
    }

    @Test
    void threeWorkingKeysSignRealDocumentsCheckedThroughTheKeyset() throws Exception {
        Keyset v1 = setup();
        signDocuments("", "servers", "pack", "launcher");
        assertServersOk("", v1, now);
        assertPackOk("", v1, now);
        assertLauncherOk("", v1, now);

        // wrong key: every document signed by a key of another role is refused
        signDocuments("-wrong", "pack", "launcher", "servers");
        assertServersRefused("-wrong", v1, now);
        assertPackRefused("-wrong", v1, now);
        assertLauncherRefused("-wrong", v1, now);

        // expired key: 31 days later the working keys (30 days) are past their expiry, the keyset (45 days) is not
        Instant later = now.plus(Duration.ofDays(31));
        assertFalse(v1.expired(later));
        assertServersRefused("", v1, later);
        assertPackRefused("", v1, later);
        assertLauncherRefused("", v1, later);

        // revoked keys (stolen): one command each
        run("keyset", "compromised", "--src", "src.json", "--id", "servers-1", "--pub", "keys/servers2.pub",
                "--root-key", "keys/root_a.key", "--roots", "roots.txt", "--prev", "ks1.json", "--out", "ks2.json");
        run("keyset", "compromised", "--src", "src.json", "--id", "launcher-1", "--pub", "keys/launcher2.pub",
                "--root-key", "keys/root_a.key", "--roots", "roots.txt", "--prev", "ks2.json", "--out", "ks3.json");
        Keyset v3 = open("ks3.json", open("ks2.json", v1));
        assertServersRefused("", v3, now);
        assertLauncherRefused("", v3, now);
        assertPackOk("", v3, now);

        // key of the old keyset: planned pack rotation, the old manifest stops passing, the new one passes
        run("keyset", "add-key", "--src", "src.json", "--role", "pack", "--pub", "keys/pack2.pub", "--id", "pack-2");
        run("keyset", "revoke", "--src", "src.json", "--id", "pack-1");
        run("keyset", "sign", "--src", "src.json", "--root-key", "keys/root_a.key", "--roots", "roots.txt", "--prev", "ks3.json", "--out", "ks4.json");
        Keyset v4 = open("ks4.json", v3);
        assertPackRefused("", v4, now);
        signDocuments("-new", "servers2", "pack2", "launcher2");
        assertServersOk("-new", v4, now);
        assertPackOk("-new", v4, now);
        assertLauncherOk("-new", v4, now);
        // the old keyset is a rollback now
        assertEquals(KeysetException.Reason.ROLLBACK, refused("ks1.json", v4));
    }

    /** A lost or stolen: emergency root e alone revokes A in a new epoch and delegates A'. */
    private void recoverWorkingRootWith(String e) throws Exception {
        Keyset v1 = setup();
        signDocuments("", "servers", "pack", "launcher");
        // the thief signs a keyset with A (higher version, an evil launcher key); some launchers take it
        Files.copy(work.resolve("src.json"), work.resolve("evil.src.json"));
        run("keyset", "add-key", "--src", "evil.src.json", "--role", "launcher", "--pub", "keys/launcher2.pub", "--id", "evil");
        run("keyset", "sign", "--src", "evil.src.json", "--root-key", "keys/root_a.key", "--out", "evil.json");
        Keyset victim = open("evil.json", v1);

        run("keyset", "emergency", "--src", "src.json", "--root-key", "keys/root_" + e + ".key", "--revoke-root", "keys/root_a.pub",
                "--add-root", "keys/root_a2.pub", "--roots", "roots.txt", "--prev", "ks1.json", "--out", "e1.json");
        Keyset up = open("e1.json", v1);
        assertTrue(up.byEmergency());
        assertEquals(1, up.epoch());
        open("e1.json", null);           // fresh install
        Keyset healed = open("e1.json", victim); // the launcher that took the thief's keyset
        assertTrue(healed.keys(Keyset.LAUNCHER, now).stream().noneMatch(k -> java.util.Arrays.equals(k,
                Ed25519.raw(pubSafe("launcher2")))), "the thief's launcher key is gone");

        // A is useless now: its keysets (any version, also re-signed in the new epoch) are refused
        assertTrue(refusedByTool("keyset", "sign", "--src", "src.json", "--root-key", "keys/root_a.key", "--roots", "roots.txt",
                "--prev", "e1.json", "--out", "x.json"));
        Files.copy(work.resolve("src.json"), work.resolve("a.src.json"));
        run("keyset", "sign", "--src", "a.src.json", "--root-key", "keys/root_a.key", "--out", "byA.json"); // unchecked on purpose
        assertEquals(KeysetException.Reason.BAD_SIGNATURE, refused("byA.json", up));
        refused("evil.json", up); // signed by the revoked A (and an older epoch)
        refused("ks1.json", up);

        // A' signs the monthly keyset: current, missed (old epoch) and fresh launchers accept it (anchors)
        run("keyset", "sign", "--src", "src.json", "--root-key", "keys/root_a2.key", "--roots", "roots.txt", "--prev", "e1.json", "--out", "m1.json");
        Keyset m = open("m1.json", up);
        assertTrue(open("m1.json", null).anchored());
        open("m1.json", v1);
        open("m1.json", victim);
        // the three working keys still work through the new chain
        assertServersOk("", m, now);
        assertPackOk("", m, now);
        assertLauncherOk("", m, now);
    }

    private String pubSafe(String name) {
        try {
            return pub(name);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void workingRootLostOrStolenRecoveredByB() throws Exception {
        recoverWorkingRootWith("b");
    }

    @Test
    void workingRootLostOrStolenRecoveredByC() throws Exception {
        recoverWorkingRootWith("c");
    }

    /** Emergency root lost (or stolen) -> the other one revokes it and adds a replacement. */
    private void replaceEmergencyRoot(String lost, String other) throws Exception {
        Keyset v1 = setup();
        assertTrue(refusedByTool("keyset", "emergency", "--src", "src.json", "--root-key", "keys/root_" + lost + ".key",
                "--revoke-root", "keys/root_" + lost + ".pub", "--roots", "roots.txt", "--prev", "ks1.json", "--out", "x.json"),
                "a root signing its own revocation");
        run("keyset", "emergency", "--src", "src.json", "--root-key", "keys/root_" + other + ".key", "--revoke-root",
                "keys/root_" + lost + ".pub", "--add-emergency", "keys/root_" + lost + "2.pub", "--roots", "roots.txt",
                "--prev", "ks1.json", "--out", "e1.json");
        Keyset e1 = open("e1.json", v1);
        open("e1.json", null);
        // the lost root is refused from now on, whatever it signs
        Files.copy(work.resolve("src.json"), work.resolve("l.src.json"));
        run("keyset", "sign", "--src", "l.src.json", "--root-key", "keys/root_" + lost + ".key", "--epoch-bump", "--out", "byLost.json");
        assertEquals(KeysetException.Reason.BAD_SIGNATURE, refused("byLost.json", e1));
        // A keeps signing monthly keysets; fresh launchers get the root change through the anchor
        run("keyset", "sign", "--src", "src.json", "--root-key", "keys/root_a.key", "--roots", "roots.txt", "--prev", "e1.json", "--out", "m1.json");
        Keyset m1 = open("m1.json", e1);
        assertTrue(open("m1.json", null).anchored());
        // the replacement emergency root starts the next epoch; fresh and old launchers accept it
        run("keyset", "emergency", "--src", "src.json", "--root-key", "keys/root_" + lost + "2.key", "--roots", "roots.txt",
                "--prev", "m1.json", "--out", "e2.json");
        assertEquals(2, open("e2.json", m1).epoch());
        assertTrue(open("e2.json", null).anchored());
        open("e2.json", v1);
        assertEquals(KeysetException.Reason.ROLLBACK, refused("m1.json", open("e2.json", m1)));
    }

    @Test
    void emergencyRootBLostReplacedByC() throws Exception {
        replaceEmergencyRoot("b", "c");
    }

    @Test
    void emergencyRootCLostReplacedByB() throws Exception {
        replaceEmergencyRoot("c", "b");
    }

    @Test
    void onlyAnEmergencyRootChangesTheEpochAndUnknownRootsAreRefused() throws Exception {
        Keyset v1 = setup();
        assertTrue(refusedByTool("keyset", "sign", "--src", "src.json", "--root-key", "keys/root_a.key", "--roots", "roots.txt",
                "--prev", "ks1.json", "--epoch-bump", "--out", "x.json"));
        assertEquals(KeysetException.Reason.NEEDS_EMERGENCY, refused("x.json.tmp", v1));
        Files.copy(work.resolve("src.json"), work.resolve("x.src.json"));
        run("keyset", "sign", "--src", "x.src.json", "--root-key", "keys/root_x.key", "--out", "byX.json");
        assertEquals(KeysetException.Reason.BAD_SIGNATURE, refused("byX.json", v1));
        assertEquals(KeysetException.Reason.BAD_SIGNATURE, refused("byX.json", null));
    }
}
