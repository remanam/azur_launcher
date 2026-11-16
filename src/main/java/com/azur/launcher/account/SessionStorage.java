package com.azur.launcher.account;

import com.azur.launcher.auth.StringVault;
import com.google.gson.Gson;

import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

/** Where the {@link SiteSession} lives. The real one is the encrypted vault (AES-GCM, key in the OS keychain, file 0600). */
public interface SessionStorage {

    Optional<SiteSession> read();

    void write(SiteSession session) throws IOException;

    void clear() throws IOException;

    /** Vault-backed storage; a damaged or incomplete file reads as "not signed in". */
    static SessionStorage vault(StringVault vault) {
        Gson gson = new Gson();
        return new SessionStorage() {
            @Override
            public Optional<SiteSession> read() {
                try {
                    String json = vault.read();
                    SiteSession s = json.isEmpty() ? null : gson.fromJson(json, SiteSession.class);
                    return Objects.nonNull(s) && s.complete() ? Optional.of(s) : Optional.empty();
                } catch (RuntimeException e) {
                    return Optional.empty();
                }
            }

            @Override
            public void write(SiteSession session) throws IOException {
                vault.write(gson.toJson(session));
            }

            @Override
            public void clear() throws IOException {
                vault.write("");
            }
        };
    }
}
