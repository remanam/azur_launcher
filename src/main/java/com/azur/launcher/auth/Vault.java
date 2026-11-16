package com.azur.launcher.auth;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * An AES-GCM encrypted file (mode 0600). File layout: magic "AZV1", key kind (1 = random key held in the OS
 * keychain, 2 = key derived from machine-bound material), 12-byte nonce, ciphertext with tag. The header is
 * authenticated. A file copied to another machine or user cannot be decrypted with a derived key.
 */
final class Vault {

    private static final byte[] MAGIC = "AZV1".getBytes(StandardCharsets.US_ASCII);
    private static final byte KIND_KEYCHAIN = 1;
    private static final byte KIND_DERIVED = 2;
    private static final String KEY_NAME = "vault-master-key";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path file;
    private final SecretStore keychain;
    private final String machineMaterial;

    /** @param keychain the OS secret store, or null when none works */
    Vault(Path file, SecretStore keychain, String machineMaterial) {
        this.file = file;
        this.keychain = keychain;
        this.machineMaterial = machineMaterial;
    }

    Path file() {
        return file;
    }

    boolean exists() {
        return Files.isRegularFile(file);
    }

    /** Decrypts the file; empty when it is missing, damaged or its key is gone (the user signs in again). */
    Optional<byte[]> read() {
        try {
            if (!exists()) {
                return Optional.empty();
            }
            ByteBuffer in = ByteBuffer.wrap(Files.readAllBytes(file));
            byte[] magic = new byte[MAGIC.length];
            in.get(magic);
            byte kind = in.get();
            if (!Arrays.equals(magic, MAGIC)) {
                return Optional.empty();
            }
            byte[] nonce = new byte[12];
            in.get(nonce);
            byte[] body = new byte[in.remaining()];
            in.get(body);
            byte[] key = kind == KIND_KEYCHAIN ? keychainKey(false) : derivedKey();
            if (Objects.isNull(key)) {
                return Optional.empty();
            }
            return Optional.of(crypt(Cipher.DECRYPT_MODE, key, nonce, header(kind), body));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    void write(byte[] plain) throws IOException {
        byte kind = KIND_KEYCHAIN;
        byte[] key = keychainKey(true);
        if (Objects.isNull(key)) {
            kind = KIND_DERIVED;
            key = derivedKey();
        }
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        byte[] body;
        try {
            body = crypt(Cipher.ENCRYPT_MODE, key, nonce, header(kind), plain);
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot encrypt the account vault", e);
        }
        ByteBuffer out = ByteBuffer.allocate(MAGIC.length + 1 + nonce.length + body.length);
        out.put(header(kind)).put(nonce).put(body);
        store(out.array());
    }

    private static byte[] header(byte kind) {
        return ByteBuffer.allocate(MAGIC.length + 1).put(MAGIC).put(kind).array();
    }

    private static byte[] crypt(int mode, byte[] key, byte[] nonce, byte[] aad, byte[] data)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(data);
    }

    /** The random master key from the keychain; created on first use when {@code create} is set. */
    private byte[] keychainKey(boolean create) {
        if (Objects.isNull(keychain)) {
            return null;
        }
        Optional<String> stored = keychain.get(KEY_NAME);
        if (stored.isPresent()) {
            try {
                byte[] key = Base64.getDecoder().decode(stored.get());
                if (key.length == 32) {
                    return key;
                }
            } catch (IllegalArgumentException ignored) {
                // damaged entry: treated as missing below
            }
        }
        if (!create) {
            return null;
        }
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        try {
            keychain.put(KEY_NAME, Base64.getEncoder().encodeToString(key));
        } catch (IOException e) {
            return null;
        }
        return keychain.get(KEY_NAME).isPresent() ? key : null;
    }

    private byte[] derivedKey() {
        try {
            PBEKeySpec spec = new PBEKeySpec(machineMaterial.toCharArray(),
                    "azur-launcher-vault-v1".getBytes(StandardCharsets.UTF_8), 210_000, 256);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        }
    }

    private void store(byte[] bytes) throws IOException {
        Path parent = file.getParent();
        Files.createDirectories(parent);
        restrict(parent, "rwx------");
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        restrict(tmp, "rw-------");
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void restrict(Path path, String perms) {
        try {
            Set<PosixFilePermission> set = PosixFilePermissions.fromString(perms);
            Files.setPosixFilePermissions(path, set);
        } catch (UnsupportedOperationException | IOException ignored) {
            // not a POSIX file system (Windows): the user profile ACL applies
        }
    }
}
