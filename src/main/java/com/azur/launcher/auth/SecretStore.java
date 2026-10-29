package com.azur.launcher.auth;

import java.io.IOException;
import java.util.Optional;

/** Keeps one small secret (the vault master key) in a place the OS protects. */
public interface SecretStore {

    Optional<String> get(String name);

    void put(String name, String value) throws IOException;
}
