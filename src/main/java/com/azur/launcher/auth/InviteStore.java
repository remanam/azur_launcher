package com.azur.launcher.auth;

import java.io.IOException;
import java.nio.file.Path;

/** The invite code of the pack server in the same encrypted vault format as the accounts (never plain text). */
public final class InviteStore {

    private final StringVault vault;

    public static InviteStore system(Path file) {
        return new InviteStore(StringVault.system(file));
    }

    /** Test and legacy entry point: wrap an existing vault. */
    InviteStore(Vault vault) {
        this(new StringVault(vault));
    }

    InviteStore(StringVault vault) {
        this.vault = vault;
    }

    /** The stored code, or an empty string. */
    public String read() {
        return vault.read();
    }

    /** An empty code removes the vault file. */
    public void write(String invite) throws IOException {
        vault.write(invite);
    }
}
