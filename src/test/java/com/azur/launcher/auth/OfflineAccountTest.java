package com.azur.launcher.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.azur.launcher.game.LaunchContext;
import com.azur.launcher.game.LaunchProfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OfflineAccountTest {

    @Test
    void nicknameRulesAreThreeToSixteenWordChars() {
        for (String ok : new String[] {"Bob", "Steve_01", "A".repeat(16)}) {
            assertTrue(OfflineAccount.isValidName(ok), ok);
        }
        for (String bad : new String[] {null, "", "ab", "A".repeat(17), "a b", "Ян_Ян", "a-b", "x;y"}) {
            assertFalse(OfflineAccount.isValidName(bad), String.valueOf(bad));
            assertThrows(IllegalArgumentException.class, () -> OfflineAccount.of(bad));
        }
        assertEquals("Bob", OfflineAccount.of("  Bob ").getUsername());
    }

    @Test
    void uuidMatchesTheVanillaServerValue() {
        // vanilla: UUID.nameUUIDFromBytes("OfflinePlayer:<name>"); the well-known value for "Notch"
        assertEquals(UUID.nameUUIDFromBytes("OfflinePlayer:Notch".getBytes()).toString(),
                OfflineAccount.of("Notch").getUuid());
        assertEquals("b50ad385-829d-3141-a216-7e7d7539ba7f", OfflineAccount.of("Notch").getUuid());
    }

    @Test
    void launchArgumentsCarryNoRealToken() {
        OfflineAccount account = OfflineAccount.of("Bob");
        assertTrue(account.launchPermitted());
        assertEquals("0", account.getAccessToken());
        assertEquals("legacy", account.getUserType());
        LaunchProfile profile = new LaunchProfile();
        profile.setAccount(LaunchContext.from(account));
        var ph = profile.toPlaceholders();
        assertEquals("0", ph.get("${auth_access_token}"));
        assertEquals("legacy", ph.get("${user_type}"));
        assertEquals("Bob", ph.get("${auth_player_name}"));
    }

    @Test
    void savedAccountsSurviveReloadAndStoreNoToken(@TempDir Path dir) throws IOException {
        Path legacy = dir.resolve("accounts.json");
        AccountStore store = new AccountStore(legacy, null, "machine");
        assertNull(store.selected());
        store.selectOffline(OfflineAccount.of("Bob"));
        store.selectOffline(OfflineAccount.of("Bob"));
        store.selectOffline(OfflineAccount.of("Alice"));
        store.save();

        AccountStore reloaded = new AccountStore(legacy, null, "machine");
        assertEquals(2, reloaded.offlineAccounts().size());
        assertEquals("Alice", reloaded.selected().getUsername());
        reloaded.selectExisting(OfflineAccount.of("Bob"));
        assertEquals("Bob", reloaded.selected().getUsername());
        reloaded.remove(OfflineAccount.uuidFor("Bob"));
        assertEquals(1, reloaded.offlineAccounts().size());
        assertNull(reloaded.selected());
    }
}
