package com.azur.launcher.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountStoreTest {

    /** In-memory stand-in for the OS keychain. */
    private static final class MemoryKeychain implements SecretStore {
        final Map<String, String> items = new HashMap<>();

        @Override
        public Optional<String> get(String name) {
            return Optional.ofNullable(items.get(name));
        }

        @Override
        public void put(String name, String value) {
            items.put(name, value);
        }
    }

    private static MicrosoftAccount alex() {
        return new MicrosoftAccount("Alex", "uuid-1", "SECRET-ACCESS", "SECRET-REFRESH", "xuid", "client", 123L);
    }

    @Test
    void selectedAccountSurvivesASaveAndReloadWithKeychainKey(@TempDir Path dir) throws IOException {
        Path legacy = dir.resolve("accounts").resolve("accounts.json");
        MemoryKeychain keychain = new MemoryKeychain();
        AccountStore store = new AccountStore(legacy, keychain, "machine");
        assertTrue(store.isEmpty());
        store.select(alex());
        store.save();
        assertEquals(1, keychain.items.size());

        AccountStore reloaded = new AccountStore(legacy, keychain, "machine");
        assertEquals("Alex", reloaded.selectedAccount().getUsername());
        reloaded.remove("uuid-1");
        assertNull(reloaded.selectedAccount());
        assertTrue(reloaded.isEmpty());
    }

    @Test
    void vaultFileHoldsNoPlainTokensAndIsOwnerOnly(@TempDir Path dir) throws IOException {
        Path legacy = dir.resolve("accounts.json");
        AccountStore store = new AccountStore(legacy, null, "machine");
        store.select(alex());
        store.save();
        Path vault = dir.resolve("accounts.vault");
        String raw = new String(Files.readAllBytes(vault), StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains("SECRET"));
        assertFalse(raw.contains("Alex"));
        if (Files.getFileStore(vault).supportsFileAttributeView("posix")) {
            assertEquals(java.util.Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
                    Files.getPosixFilePermissions(vault));
        }
        assertEquals("Alex", new AccountStore(legacy, null, "machine").selectedAccount().getUsername());
    }

    @Test
    void derivedKeyVaultDoesNotOpenOnAnotherMachine(@TempDir Path dir) throws IOException {
        Path legacy = dir.resolve("accounts.json");
        AccountStore store = new AccountStore(legacy, null, "machine-A");
        store.select(alex());
        store.save();
        assertTrue(new AccountStore(legacy, null, "machine-B").isEmpty());
    }

    @Test
    void tamperedVaultIsIgnored(@TempDir Path dir) throws IOException {
        Path legacy = dir.resolve("accounts.json");
        AccountStore store = new AccountStore(legacy, null, "m");
        store.select(alex());
        store.save();
        Path vault = dir.resolve("accounts.vault");
        byte[] bytes = Files.readAllBytes(vault);
        bytes[bytes.length - 1] ^= 1;
        Files.write(vault, bytes);
        assertTrue(new AccountStore(legacy, null, "m").isEmpty());
    }

    @Test
    void plainLegacyJsonIsMigratedAndRemoved(@TempDir Path dir) throws IOException {
        Path legacy = dir.resolve("accounts.json");
        Files.writeString(legacy, "{\"microsoft\":[{\"name\":\"Old\",\"uuid\":\"u-9\",\"accessToken\":\"t\","
                + "\"refreshToken\":\"r\",\"notAfter\":5}],\"selectedUuid\":\"u-9\"}");
        AccountStore store = new AccountStore(legacy, null, "m");
        assertEquals("Old", store.selectedAccount().getUsername());
        assertEquals(0L, store.selectedAccount().getOwnershipVerifiedAt());
        assertFalse(Files.exists(legacy));
        assertTrue(Files.exists(dir.resolve("accounts.vault")));
        assertEquals("Old", new AccountStore(legacy, null, "m").selectedAccount().getUsername());
    }

    @Test
    void keychainToolsGetTheSecretOnStdinNotInArguments(@TempDir Path dir) throws IOException {
        StringBuilder stdin = new StringBuilder();
        List<String> seen = new java.util.ArrayList<>();
        CommandRunner runner = (command, in) -> {
            seen.addAll(command);
            stdin.append(in);
            return new CommandRunner.Result(0, "");
        };
        OsKeychain.forTests("MAC", runner, dir).put("k", "VALUE");
        assertFalse(seen.contains("VALUE"));
        assertTrue(stdin.toString().contains("VALUE"));
        seen.clear();
        stdin.setLength(0);
        OsKeychain.forTests("LINUX", runner, dir).put("k", "VALUE");
        assertFalse(seen.contains("VALUE"));
        assertEquals("VALUE", stdin.toString());
    }
}
