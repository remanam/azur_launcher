package com.azur.launcher.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** The invite code of the pack server in the same encrypted vault format as the accounts (never plain text). */
public final class InviteStore {

    private final Vault vault;

    public static InviteStore system(Path file) {
        return new InviteStore(new Vault(file, OsKeychain.forCurrentOs(file.getParent()).orElse(null),
                MachineMaterial.current()));
    }

    InviteStore(Vault vault) {
        this.vault = vault;
    }

    /** The stored code, or an empty string. */
    public String read() {
        return vault.read().map(bytes -> new String(bytes, StandardCharsets.UTF_8)).orElse("");
    }

    /** An empty code removes the vault file. */
    public void write(String invite) throws IOException {
        String code = invite == null ? "" : invite.trim();
        if (code.isEmpty()) {
            Files.deleteIfExists(vaultFile());
            return;
        }
        vault.write(code.getBytes(StandardCharsets.UTF_8));
    }

    private Path vaultFile() {
        return vault.file();
    }
}
