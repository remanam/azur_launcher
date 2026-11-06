package com.azur.launcher.account;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/** Small secret files handed to the game: created with mode 0600 from the start (no window where others can read them). */
final class SecretFile {

    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private SecretFile() {
    }

    static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.deleteIfExists(file);
        try {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        } catch (UnsupportedOperationException e) {
            Files.createFile(file); // Windows: the profile directory is the protection
        }
        Files.write(file, text.getBytes(StandardCharsets.UTF_8));
    }
}
