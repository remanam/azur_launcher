package com.azur.launcher.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountStoreTest {

    @Test
    void selectedAccountSurvivesASaveAndReload(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("accounts").resolve("accounts.json");
        AccountStore store = new AccountStore(file);
        assertTrue(store.isEmpty());
        store.select(new MicrosoftAccount("Alex", "uuid-1", "token", "refresh", "xuid", "client", 123L));
        store.save();

        AccountStore reloaded = new AccountStore(file);
        assertEquals("Alex", reloaded.selectedAccount().getUsername());
        reloaded.remove("uuid-1");
        assertNull(reloaded.selectedAccount());
        assertTrue(reloaded.isEmpty());
    }
}
