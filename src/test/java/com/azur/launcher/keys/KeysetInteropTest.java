package com.azur.launcher.keys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.azur.launcher.update.UpdateException;
import com.azur.launcher.update.UpdateManifest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The real Go tool (tools/updater azur-servers) makes keys, keyset and launcher.json; Java verifies them. Skipped without Go. */
class KeysetInteropTest {

    @TempDir
    Path work;

    private Path tool;

    private String run(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(List.of(tool.toString()));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(work.toFile()).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IOException(String.join(" ", args) + ": " + out);
        }
        return out;
    }

    private boolean buildTool() {
        tool = work.resolve("azur-servers");
        try {
            Process p = new ProcessBuilder("go", "build", "-o", tool.toString(), "./cmd/azur-servers")
                    .directory(Path.of("..", "tools", "updater").toFile()).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor(180, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Test
    void goKeysetAndLauncherSignatureVerifyInJava() throws Exception {
        assumeTrue(buildTool(), "Go toolchain not available");
        for (String r : List.of("root_primary", "root_reserve", "launcher", "pack")) {
            run("keygen", "--role", r, "--out", "keys/" + r + ".key");
        }
        String roots = "primary " + Files.readString(work.resolve("keys/root_primary.pub")).trim() + "\nreserve "
                + Files.readString(work.resolve("keys/root_reserve.pub")).trim() + "\n";
        Files.writeString(work.resolve("roots.txt"), roots);
        run("keyset", "new", "--src", "src.json");
        run("keyset", "add-key", "--src", "src.json", "--role", "launcher", "--pub", "keys/launcher.pub", "--id", "launcher-1");
        run("keyset", "add-key", "--src", "src.json", "--role", "pack", "--pub", "keys/pack.pub", "--id", "pack-1");
        run("keyset", "sign", "--src", "src.json", "--root-key", "keys/root_primary.key", "--roots", "roots.txt", "--out", "ks1.json");

        RootKeys rootKeys = RootKeys.parse(roots);
        Instant now = Instant.now();
        Keyset v1 = Keyset.open(Files.readAllBytes(work.resolve("ks1.json")), rootKeys, null, now);
        assertEquals(1, v1.version());
        assertEquals(1, v1.keys(Keyset.PACK, now).size());

        Files.writeString(work.resolve("l.json"), "{\"version\":\"1.0.7\",\"sha256\":\"" + "0".repeat(64)
                + "\",\"size\":5,\"date\":\"2026-11-05\",\"url\":\"https://example.test/dl/azur-launcher.jar\","
                + "\"min_wrapper\":\"1.0.0\",\"required\":false,\"notes\":\"Исправления\"}");
        run("sign-launcher", "--no-legacy", "--add-key", "keys/launcher.key", "--in", "l.json", "--out", "l.signed.json");
        byte[] launcherJson = Files.readAllBytes(work.resolve("l.signed.json"));
        List<java.security.PublicKey> keys = Ed25519.publicKeys(v1.keys(Keyset.LAUNCHER, now));
        assertEquals("1.0.7", UpdateManifest.openWithKeys(launcherJson, keys).version());

        // "the launcher key was stolen": one command, then Java drops the key
        run("keygen", "--role", "launcher", "--out", "keys/launcher2.key");
        run("keyset", "compromised", "--src", "src.json", "--id", "launcher-1", "--pub", "keys/launcher2.pub",
                "--root-key", "keys/root_primary.key", "--roots", "roots.txt", "--prev", "ks1.json", "--out", "ks2.json");
        Keyset v2 = Keyset.open(Files.readAllBytes(work.resolve("ks2.json")), rootKeys, v1, now);
        assertThrows(UpdateException.class, () -> UpdateManifest.openWithKeys(launcherJson,
                Ed25519.publicKeys(v2.keys(Keyset.LAUNCHER, now))));
        assertThrows(KeysetException.class, () -> Keyset.open(Files.readAllBytes(work.resolve("ks1.json")), rootKeys, v2, now));
    }
}
