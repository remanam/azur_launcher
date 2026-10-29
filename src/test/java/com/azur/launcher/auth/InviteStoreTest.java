package com.azur.launcher.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InviteStoreTest {

    @Test
    void codeIsEncryptedOnDiskAndReadsBack(@TempDir Path tmp) throws IOException {
        Path file = tmp.resolve("invite.vault");
        InviteStore store = new InviteStore(new Vault(file, null, "machine"));
        assertEquals("", store.read());
        store.write(" azur1.secret-code ");
        assertEquals("azur1.secret-code", store.read());
        assertFalse(new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1)
                .contains("secret-code"));
        store.write("");
        assertFalse(Files.exists(file));
        assertTrue(store.read().isEmpty());
    }
}
