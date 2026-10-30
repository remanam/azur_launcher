package com.azur.launcher.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Windows DPAPI branch, which cannot be executed on this machine: the blob file round trip and the behaviour when the
 * tool is missing or fails. The command runner is the only OS dependency, so a fake covers the whole logic.
 */
class OsKeychainTest {

    private static final String BLOB = "01000000d08c9ddf0115d1118c7a00c04fc297eb";

    @Test
    void windowsPutWritesTheBlobAndGetReadsItBack(@TempDir Path dir) throws IOException {
        List<List<String>> calls = new ArrayList<>();
        CommandRunner fake = (command, stdin) -> {
            calls.add(command);
            String script = String.join(" ", command);
            return script.contains("$s = ") ? new CommandRunner.Result(0, "secret\n")
                    : new CommandRunner.Result(0, BLOB + "\n");
        };
        OsKeychain keychain = OsKeychain.forTests("WINDOWS", fake, dir);

        keychain.put("accounts", "secret");

        assertEquals(BLOB, Files.readString(dir.resolve("accounts.dpapi")).strip());
        assertEquals(Optional.of("secret"), keychain.get("accounts"));
        assertTrue(calls.stream().allMatch(call -> "powershell".equals(call.get(0))));
    }

    @Test
    void missingBlobIsNoValueNotAnError(@TempDir Path dir) throws IOException {
        OsKeychain keychain = OsKeychain.forTests("WINDOWS", (command, stdin) -> {
            throw new AssertionError("the tool must not run when there is no blob");
        }, dir);
        assertEquals(Optional.empty(), keychain.get("accounts"));
    }

    @Test
    void failingToolIsNoValueForReadsAndAnErrorForWrites(@TempDir Path dir) throws IOException {
        OsKeychain keychain = OsKeychain.forTests("WINDOWS",
                (command, stdin) -> new CommandRunner.Result(1, "denied"), dir);

        Files.writeString(dir.resolve("accounts.dpapi"), BLOB);
        assertEquals(Optional.empty(), keychain.get("accounts"));
        assertThrows(IOException.class, () -> keychain.put("accounts", "secret"));
    }

    @Test
    void macAndLinuxUseTheirOwnTools(@TempDir Path dir) throws IOException {
        List<String> used = new ArrayList<>();
        CommandRunner fake = (command, stdin) -> {
            used.add(command.get(0));
            return new CommandRunner.Result(0, "value\n");
        };

        assertEquals(Optional.of("value"), OsKeychain.forTests("MAC", fake, dir).get("accounts"));
        assertEquals(Optional.of("value"), OsKeychain.forTests("LINUX", fake, dir).get("accounts"));
        assertEquals(List.of("security", "secret-tool"), used);
    }
}
