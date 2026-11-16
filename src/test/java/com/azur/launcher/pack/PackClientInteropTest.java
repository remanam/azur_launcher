package com.azur.launcher.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.azur.launcher.net.HostPolicy;
import com.azur.launcher.sync.PackSyncService;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Java client against manifests signed and served by the real Go {@code azur-dist}. */
class PackClientInteropTest {

    private static final HostPolicy LOCAL = HostPolicy.forTests(Set.of("127.0.0.1"));

    @TempDir
    static Path build;
    @TempDir
    Path work;

    @BeforeAll
    static void buildGo() {
        assumeTrue(Objects.nonNull(GoPackFixture.binary(build)), "Go toolchain not available: interop tests skipped");
    }

    private Path pack(String version, String core, String extra) throws IOException {
        Path p = Files.createDirectories(work.resolve("pack-" + version));
        GoPackFixture.write(p, "mods/azur_core-1.jar", core);
        GoPackFixture.write(p, "config/azur_core.toml", "a=" + version);
        GoPackFixture.write(p, "config/player.toml", "default=" + version);
        GoPackFixture.write(p, "config/lines.txt", "base\n" + extra);
        if (version.equals("1")) {
            GoPackFixture.write(p, "mods/lib-1.jar", "lib");
        }
        GoPackFixture.write(p, "mods/user_pick.jar", "pack-version-of-user-file");
        GoPackFixture.write(p, "pack.json", "{\"pack_version\":\"" + version + "\",\"policy\":{"
                + "\"config/azur_*\":\"overwrite\",\"mods/*\":\"overwrite\",\"config/player.toml\":\"keep-if-exists\","
                + "\"config/lines.txt\":\"merge-list\"},\"user_owned\":[\"mods/user_*.jar\"]"
                + (version.equals("2") ? ",\"retired\":[\"mods/old.jar\"]" : "") + "}");
        return p;
    }

    private Path instance(String name) throws IOException {
        return Files.createDirectories(work.resolve(name).resolve("minecraft"));
    }

    /** The pack entry of a Go-signed servers.json, read by the launcher's own verifier. */
    private PackSource sourceOf(GoPackFixture go, String url, String key) throws Exception {
        String json = "{\"schema\":2,\"validUntil\":\"2099-01-01T00:00:00Z\",\"servers\":[{\"id\":\"t\","
                + "\"name\":\"T\",\"address\":\"x.example\",\"pack\":{\"url\":\"" + url + "\",\"key\":\"" + key + "\"}}]}";
        byte[] env = go.signServers(build, json);
        var list = com.azur.launcher.servers.ServerListVerifier.open(env,
                com.azur.launcher.servers.ServerListVerifier.publicKey(go.publicKey(build)),
                java.time.Instant.now());
        return list.servers().get(0).pack();
    }

    private PackSource source(GoPackFixture go) throws Exception {
        return sourceOf(go, go.url(), go.publicKey(build));
    }

    private PackClient client() {
        return new PackClient(work.resolve("home"), LOCAL);
    }

    private static String read(Path game, String rel) throws IOException {
        return Files.readString(game.resolve(rel));
    }

    private static String sha(String s) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void syncDeltaDeleteListAndRollback() throws Exception {
        try (GoPackFixture go = GoPackFixture.create(work)) {
            go.publish(pack("1", "core1", "one"));
            go.serve();
            PackSource invite = source(go);
            Path game = instance("a");
            Files.createDirectories(game.resolve("mods"));
            Files.writeString(game.resolve("mods/my_extra.jar"), "mine");
            Files.writeString(game.resolve("mods/user_pick.jar"), "players-own");
            Files.createDirectories(game.resolve("config"));
            Files.writeString(game.resolve("config/player.toml"), "my=settings");
            Files.writeString(game.resolve("config/lines.txt"), "base\nmine\n");
            Files.createDirectories(game.resolve("saves"));
            Files.writeString(game.resolve("saves/level.dat"), "world");
            Files.writeString(game.resolve("mods/old.jar"), "old");

            PackClient c = client();
            List<String> phases = new CopyOnWriteArrayList<>();
            PackClient.Checked checked = c.check(invite, game);
            assertTrue(checked.plan().hasChanges());
            assertEquals("1", c.update(checked, game, (ph, f, d, t) -> phases.add(ph)));
            assertTrue(phases.contains("install"));
            assertEquals("core1", read(game, "mods/azur_core-1.jar"));
            assertEquals("lib", read(game, "mods/lib-1.jar"));
            assertEquals("a=1", read(game, "config/azur_core.toml"));
            assertEquals("my=settings", read(game, "config/player.toml"), "keep-if-exists keeps the player's config");
            assertEquals("players-own", read(game, "mods/user_pick.jar"), "user_owned is never touched");
            assertTrue(read(game, "config/lines.txt").contains("one") && read(game, "config/lines.txt").contains("mine"));
            assertEquals("mine", read(game, "mods/my_extra.jar"));
            assertEquals("world", read(game, "saves/level.dat"));
            assertFalse(Files.exists(game.resolve(".azur-cache")));
            assertEquals("1", c.verify(game));
            assertTrue(c.check(invite, game).plan().items().stream().noneMatch(i -> i.action() == PackPlan.Action.ADD
                || i.action() == PackPlan.Action.UPDATE || i.action() == PackPlan.Action.REMOVE),
                "a second check finds nothing to download or delete");

            go.publish(pack("2", "core2", "two"));
            PackClient.Checked second = c.check(invite, game);
            assertEquals("2", c.update(second, game, PackProgress.NONE));
            assertEquals("core2", read(game, "mods/azur_core-1.jar"));
            assertFalse(Files.exists(game.resolve("mods/lib-1.jar")), "a file that left the pack is deleted");
            assertFalse(Files.exists(game.resolve("mods/old.jar")), "the retired list is applied");
            assertEquals("mine", read(game, "mods/my_extra.jar"));
            assertEquals("players-own", read(game, "mods/user_pick.jar"));
            assertEquals("2", c.verify(game));

            Files.writeString(game.resolve("mods/azur_core-1.jar"), "tampered locally");
            assertEquals(PackException.VERIFY, assertThrows(PackException.class, () -> c.verify(game)).code());

            assertEquals("1", c.rollback(game));
            assertEquals("core1", read(game, "mods/azur_core-1.jar"));
            assertEquals("lib", read(game, "mods/lib-1.jar"));
            assertEquals("old", read(game, "mods/old.jar"));
            assertEquals("1", c.verify(game));
        }
    }

    @Test
    void tamperedStoredFileIsRejectedAndNothingIsInstalled() throws Exception {
        try (GoPackFixture go = GoPackFixture.create(work)) {
            go.publish(pack("1", "core1", "one"));
            go.serve();
            Files.writeString(go.object(sha("core1")), "evil!"); // same size, other bytes
            Path game = instance("t");
            PackClient c = client();
            PackException e = assertThrows(PackException.class, () -> c.update(c.check(source(go), game), game,
                    PackProgress.NONE));
            assertEquals(PackException.NETWORK, e.code());
            assertFalse(Files.exists(game.resolve("mods/azur_core-1.jar")));
        }
    }

    @Test
    void wrongKeyInTheServerEntryIsASignatureError() throws Exception {
        try (GoPackFixture go = GoPackFixture.create(work)) {
            go.publish(pack("1", "core1", "one"));
            go.serve();
            byte[] spki = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic().getEncoded();
            String other = Base64.getEncoder().encodeToString(Arrays.copyOfRange(spki, spki.length - 32, spki.length));
            PackSource bad = sourceOf(go, go.url(), other);
            assertEquals(PackException.SIGNATURE,
                    assertThrows(PackException.class, () -> client().check(bad, instance("w"))).code());
        }
    }

    @Test
    void entryWithoutPackKeyIsAClearError() throws Exception {
        try (GoPackFixture go = GoPackFixture.create(work)) {
            go.publish(pack("1", "core1", "one"));
            go.serve();
            for (PackSource none : new PackSource[] {PackSource.NONE, new PackSource(go.url(), ""),
                    new PackSource(go.url(), "not-a-key")}) {
                assertEquals(PackException.NO_PACK,
                        assertThrows(PackException.class, () -> client().check(none, instance("n"))).code());
            }
        }
    }

    @Test
    void manifestAndFilesAreServedWithoutAnyAuthorisation() throws Exception {
        try (GoPackFixture go = GoPackFixture.create(work)) {
            go.publish(pack("1", "core1", "one"));
            go.serve();
            HttpClient http = HttpClient.newHttpClient();
            for (String path : List.of("/manifest.json", "/status", "/files/" + sha("core1"))) {
                assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(go.url() + path)).build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode(), path);
            }
        }
    }

    @Test
    void tamperedManifestInTransitIsRejected() throws Exception {
        try (GoPackFixture go = GoPackFixture.create(work)) {
            go.publish(pack("1", "core1", "one"));
            go.serve();
            HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            HttpClient http = HttpClient.newHttpClient();
            proxy.createContext("/", ex -> {
                try {
                    HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(go.url() + ex.getRequestURI())).build(),
                            HttpResponse.BodyHandlers.ofByteArray());
                    byte[] body = r.body();
                    if (ex.getRequestURI().getPath().equals("/manifest.json")) {
                        String json = new String(body, StandardCharsets.UTF_8);
                        String payload = json.replaceAll(".*\"payload\":\"([^\"]+)\".*", "$1");
                        String forged = new String(Base64.getDecoder().decode(payload), StandardCharsets.UTF_8)
                                .replace("core1", "xxxxx").replace("\"pack_version\":\"1\"", "\"pack_version\":\"9\"");
                        body = json.replace(payload, Base64.getEncoder()
                                .encodeToString(forged.getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.UTF_8);
                    }
                    ex.sendResponseHeaders(r.statusCode(), body.length);
                    ex.getResponseBody().write(body);
                } catch (Exception e) {
                    ex.sendResponseHeaders(502, -1);
                } finally {
                    ex.close();
                }
            });
            proxy.start();
            try {
                PackSource viaProxy = sourceOf(go, "http://127.0.0.1:" + proxy.getAddress().getPort(), go.publicKey(build));
                assertEquals(PackException.SIGNATURE,
                        assertThrows(PackException.class, () -> client().check(viaProxy, instance("m"))).code());
            } finally {
                proxy.stop(0);
            }
        }
    }

    @Test
    void maintenanceBannerAndHostPolicy() throws Exception {
        try (GoPackFixture go = GoPackFixture.create(work)) {
            go.publish(pack("1", "core1", "one"));
            go.serve();
            PackSource invite = source(go);
            Path game = instance("r");

            go.run("maintenance", "--message", "Server is updating", "on");
            PackSyncService service = new PackSyncService(work.resolve("home2"), () -> LOCAL, false);
            PackSyncService.Result ok = service.sync(invite, game, (p, f, d, t) -> { });
            assertTrue(ok.ok(), ok.message());
            assertEquals("1", ok.packVersion());
            assertEquals("Server is updating", service.maintenanceBanner().orElseThrow());
            assertTrue(service.verify(game).ok());
            assertTrue(service.rollback(game).ok());

            PackSyncService production = new PackSyncService(work.resolve("home3"), HostPolicy::official, true);
            PackSource foreign = invite.withUrl("http://127.0.0.1.evil.example:8780");
            PackSyncService.Result blocked = production.sync(foreign, instance("p"), (p, f, d, t) -> { });
            assertEquals(PackException.NETWORK, blocked.code());

        }
    }
}
