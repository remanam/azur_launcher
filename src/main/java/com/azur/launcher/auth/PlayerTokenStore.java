package com.azur.launcher.auth;

import java.io.IOException;
import java.nio.file.Path;

/**
 * The player token of the community API (registration, roles) in the same encrypted vault as the
 * accounts. The Minecraft sign-in is separate: this token never replaces it.
 */
public final class PlayerTokenStore {

    private final StringVault vault;

    public static PlayerTokenStore system(Path file) {
        return new PlayerTokenStore(StringVault.system(file));
    }

    PlayerTokenStore(StringVault vault) {
        this.vault = vault;
    }

    /** The stored token, or an empty string when the player has not registered yet. */
    public String read() {
        return vault.read();
    }

    /** An empty token removes the vault file (unregister / revoke locally). */
    public void write(String token) throws IOException {
        vault.write(token);
    }
}
