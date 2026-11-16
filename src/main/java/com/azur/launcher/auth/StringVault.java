package com.azur.launcher.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * One secret string in the encrypted vault format of the accounts ({@link Vault}: AES-GCM, key in the OS keychain or
 * derived from machine material). An empty value removes the file, so "no secret" is visible on disk.
 */
public final class StringVault {

    private final Vault vault;

    public static StringVault system(Path file) {
        return new StringVault(new Vault(file, OsKeychain.forCurrentOs(file.getParent()).orElse(null),
                MachineMaterial.current()));
    }

    StringVault(Vault vault) {
        this.vault = vault;
    }

    /** The stored secret, or an empty string. */
    public String read() {
        return vault.read().map(bytes -> new String(bytes, StandardCharsets.UTF_8)).orElse("");
    }

    /** Stores the secret trimmed; an empty value deletes the file. */
    public void write(String value) throws IOException {
        String secret = Objects.isNull(value) ? "" : value.trim();
        if (secret.isEmpty()) {
            Files.deleteIfExists(vault.file());
            return;
        }
        vault.write(secret.getBytes(StandardCharsets.UTF_8));
    }
}
